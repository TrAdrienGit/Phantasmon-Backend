package com.mystaria.phantasmon_backend.auth;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerService;

import lombok.extern.slf4j.Slf4j;

/**
 * Exchanges a Mojang session proof for a Phantasmon JWT (CAD Partie 2 §3).
 * There is no Minecraft server involved: the client already proved control of
 * the account to Mojang via {@code joinServer}; this endpoint just confirms
 * it via {@code hasJoined} and issues our own token.
 */
@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

	private final MojangSessionClient mojangSessionClient;
	private final PlayerService playerService;
	private final JwtService jwtService;
	private final AuthChallengeService challengeService;

	public AuthController(MojangSessionClient mojangSessionClient, PlayerService playerService, JwtService jwtService,
			AuthChallengeService challengeService) {
		this.mojangSessionClient = mojangSessionClient;
		this.playerService = playerService;
		this.jwtService = jwtService;
		this.challengeService = challengeService;
	}

	/** Step 1 of the login: a one-time challenge the client must use as its Mojang {@code serverId} (SEC-1). */
	@PostMapping("/challenge")
	public Map<String, String> challenge() {
		return Map.of("challenge", challengeService.issue());
	}

	@PostMapping("/session")
	public AuthSessionResponse authenticate(@Valid @RequestBody AuthSessionRequest request, HttpServletRequest httpRequest) {
		// Only a serverId we issued proves the join was made for us, not replayed by another server (SEC-1).
		if (!challengeService.consume(request.serverId())) {
			log.warn("Auth rejected for username={}: server_id is not a challenge issued by this backend", request.username());
			throw new ApiException(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_INVALID_CHALLENGE", Map.of());
		}
		MojangProfile profile = mojangSessionClient.hasJoined(request.username(), request.serverId(), httpRequest.getRemoteAddr())
				.orElseThrow(() -> {
					log.warn("Mojang verification failed for username={}", request.username());
					return new ApiException(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_MOJANG_VERIFICATION_FAILED",
							Map.of("username", request.username()));
				});

		if (!profile.uuid().equals(request.uuid())) {
			log.warn("Auth UUID mismatch: claimed={} verified={}", request.uuid(), profile.uuid());
			throw new ApiException(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_UUID_MISMATCH",
					Map.of("claimed_uuid", request.uuid(), "verified_uuid", profile.uuid()));
		}

		playerService.recordConnection(profile.uuid(), profile.name());
		log.info("Player {} ({}) authenticated via Mojang", profile.name(), profile.uuid());

		String accessToken = jwtService.issueAccessToken(profile.uuid(), profile.name());
		String refreshToken = jwtService.issueRefreshToken(profile.uuid());

		return new AuthSessionResponse(accessToken, refreshToken, jwtService.accessTokenTtlSeconds());
	}

	/**
	 * Exchanges a still-valid refresh token for a new access/refresh pair
	 * without re-verifying Mojang (CAD Partie 2 §3.2) — lets long sessions stay
	 * connected past the short access-token TTL. The refresh token is rotated
	 * on every call; note there is no server-side revocation store yet (V1),
	 * so a previously-issued refresh token remains technically valid until its
	 * own expiry even after rotation.
	 */
	@PostMapping("/refresh")
	public AuthSessionResponse refresh(@Valid @RequestBody RefreshRequest request) {
		UUID playerUuid = jwtService.parseRefreshToken(request.refreshToken())
				.map(claims -> UUID.fromString(claims.getSubject()))
				.orElseThrow(() -> {
					log.warn("Refresh rejected: invalid, expired, or wrong-typed token presented");
					return new ApiException(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_INVALID_REFRESH_TOKEN", Map.of());
				});

		Player player = playerService.findById(playerUuid)
				.orElseThrow(() -> {
					log.warn("Refresh rejected: player {} no longer exists", playerUuid);
					return new ApiException(HttpStatus.UNAUTHORIZED, "ERROR_AUTH_INVALID_REFRESH_TOKEN", Map.of());
				});

		playerService.recordConnection(playerUuid, player.getLastUsername());
		log.info("Player {} ({}) refreshed session", player.getLastUsername(), playerUuid);

		String accessToken = jwtService.issueAccessToken(playerUuid, player.getLastUsername());
		String refreshToken = jwtService.issueRefreshToken(playerUuid);

		return new AuthSessionResponse(accessToken, refreshToken, jwtService.accessTokenTtlSeconds());
	}
}
