package com.mystaria.phantasmon_backend.auth;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

	private static final String SECRET = Base64.getEncoder()
			.encodeToString("test-only-secret-key-not-for-production-use-0123456789".getBytes());

	private JwtService service(Duration accessTtl, Duration refreshTtl) {
		return new JwtService(SECRET, accessTtl, refreshTtl);
	}

	@Test
	void accessTokenRoundTripsWithSubjectAndUsername() {
		JwtService jwtService = service(Duration.ofMinutes(20), Duration.ofDays(7));
		UUID playerUuid = UUID.randomUUID();

		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		Optional<Claims> claims = jwtService.parseAccessToken(token);

		assertThat(claims).isPresent();
		assertThat(claims.get().getSubject()).isEqualTo(playerUuid.toString());
		assertThat(claims.get().get("username", String.class)).isEqualTo("Bichou");
		assertThat(claims.get().get("type", String.class)).isEqualTo("access");
	}

	@Test
	void refreshTokenIsRejectedByParseAccessToken() {
		JwtService jwtService = service(Duration.ofMinutes(20), Duration.ofDays(7));
		UUID playerUuid = UUID.randomUUID();

		String refreshToken = jwtService.issueRefreshToken(playerUuid);

		assertThat(jwtService.parseAccessToken(refreshToken)).isEmpty();
	}

	@Test
	void tamperedTokenIsRejected() {
		JwtService jwtService = service(Duration.ofMinutes(20), Duration.ofDays(7));
		String token = jwtService.issueAccessToken(UUID.randomUUID(), "Bichou");
		String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

		assertThat(jwtService.parseAccessToken(tampered)).isEmpty();
	}

	@Test
	void expiredAccessTokenIsRejected() throws InterruptedException {
		JwtService jwtService = service(Duration.ofMillis(1), Duration.ofDays(7));
		String token = jwtService.issueAccessToken(UUID.randomUUID(), "Bichou");

		Thread.sleep(50);

		assertThat(jwtService.parseAccessToken(token)).isEmpty();
	}

	@Test
	void refreshTokenRoundTripsViaParseRefreshToken() {
		JwtService jwtService = service(Duration.ofMinutes(20), Duration.ofDays(7));
		UUID playerUuid = UUID.randomUUID();

		String refreshToken = jwtService.issueRefreshToken(playerUuid);
		Optional<Claims> claims = jwtService.parseRefreshToken(refreshToken);

		assertThat(claims).isPresent();
		assertThat(claims.get().getSubject()).isEqualTo(playerUuid.toString());
		assertThat(claims.get().get("type", String.class)).isEqualTo("refresh");
	}

	@Test
	void accessTokenIsRejectedByParseRefreshToken() {
		JwtService jwtService = service(Duration.ofMinutes(20), Duration.ofDays(7));
		String accessToken = jwtService.issueAccessToken(UUID.randomUUID(), "Bichou");

		assertThat(jwtService.parseRefreshToken(accessToken)).isEmpty();
	}

	@Test
	void expiredRefreshTokenIsRejected() throws InterruptedException {
		JwtService jwtService = service(Duration.ofMinutes(20), Duration.ofMillis(1));
		String refreshToken = jwtService.issueRefreshToken(UUID.randomUUID());

		Thread.sleep(50);

		assertThat(jwtService.parseRefreshToken(refreshToken)).isEmpty();
	}

	@Test
	void accessTokenTtlSecondsMatchesConfiguredDuration() {
		JwtService jwtService = service(Duration.ofMinutes(20), Duration.ofDays(7));

		assertThat(jwtService.accessTokenTtlSeconds()).isEqualTo(1200L);
	}
}
