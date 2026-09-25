package com.mystaria.phantasmon_backend.auth;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Wraps Mojang's session server {@code hasJoined} endpoint — the same
 * primitive a real Minecraft server uses to verify a joining player, reused
 * here without needing an actual Minecraft server (CAD Partie 2 §3.1).
 */
@Component
public class MojangSessionClient {

	private final RestClient restClient;

	public MojangSessionClient(RestClient.Builder builder) {
		this.restClient = builder.baseUrl("https://sessionserver.mojang.com").build();
	}

	/**
	 * Returns the verified profile if Mojang confirms this player joined a
	 * session with this {@code serverId}, or empty if it did not confirm (bad
	 * proof, unknown player, offline-mode account, or Mojang unreachable —
	 * CAD Partie 2 §3.2, offline accounts are explicitly out of scope).
	 */
	public Optional<MojangProfile> hasJoined(String username, String serverId, String clientIp) {
		try {
			MojangProfile profile = restClient.get()
					.uri(uriBuilder -> {
						var built = uriBuilder.path("/session/minecraft/hasJoined")
								.queryParam("username", username)
								.queryParam("serverId", serverId);
						if (clientIp != null && !clientIp.isBlank()) {
							built = built.queryParam("ip", clientIp);
						}
						return built.build();
					})
					.retrieve()
					.body(MojangProfile.class);
			return Optional.ofNullable(profile);
		} catch (RestClientException ex) {
			return Optional.empty();
		}
	}
}
