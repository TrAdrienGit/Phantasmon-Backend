package com.mystaria.phantasmon_backend.websocket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;
import com.mystaria.phantasmon_backend.presence.PresenceService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTL expiry (CAD Partie 3 §F) with a 1-second TTL and the scheduled sweep pushed out of the way, so the test
 * drives {@link PresenceTtlSweeper#sweep()} itself. Separate class because it needs its own TTL properties.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"phantasmon.logging.enabled=false",
		"phantasmon.presence.ttl=PT1S",
		"phantasmon.presence.sweep-interval-ms=3600000" })
class PresenceTtlSweepIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private PresenceService presenceService;

	@Autowired
	private PokemonRepository pokemonRepository;

	@Autowired
	private PresenceTtlSweeper sweeper;

	private static class RecordingHandler extends TextWebSocketHandler {
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			received.add(message.getPayload());
		}
	}

	private WebSocketSession joinGroup(UUID playerUuid, RecordingHandler handler, String fingerprint) throws Exception {
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		WebSocketSession session = new StandardWebSocketClient()
				.execute(handler, "ws://localhost:" + port + "/ws?token=" + token).get(5, TimeUnit.SECONDS);
		session.sendMessage(new TextMessage(
				"{\"type\":\"JoinServerGroup\",\"data\":{\"server_fingerprint\":\"" + fingerprint + "\",\"dimension\":\"minecraft:overworld\"}}"));
		Thread.sleep(300);
		return session;
	}

	/** First message of the given type, skipping any other (HeartbeatAck...); null after 5 seconds. */
	private static String awaitMessage(RecordingHandler handler, String type) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			String message = handler.received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			if (message != null && message.contains("\"" + type + "\"")) {
				return message;
			}
		}
		return null;
	}

	@Test
	void expiredPresenceDespawnsItsGhostForTheRestOfTheGroup() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), aliceUuid, "pikachu", null,
				(short) 50, "timid", "static", false, null, null, (short) 1, "1.8.1", Map.of("ivs", Map.of(), "evs", Map.of())));

		RecordingHandler aliceHandler = new RecordingHandler();
		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ttl-ghost");
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-ttl-ghost");

		try {
			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));
			assertThat(awaitMessage(bobHandler, "GhostEntitySpawn")).isNotNull();

			// Alice goes silent past the TTL; Bob keeps heartbeating.
			Thread.sleep(1300);
			bobSession.sendMessage(new TextMessage("{\"type\":\"Heartbeat\",\"data\":{}}"));
			assertThat(awaitMessage(bobHandler, "HeartbeatAck")).isNotNull();

			sweeper.sweep();

			String despawn = awaitMessage(bobHandler, "GhostEntityDespawn");
			assertThat(despawn).as("the rest of the group must see the expired player's Ghost disappear")
					.isNotNull().contains(aliceUuid.toString()).contains(aliceMon.getUuid().toString());
			assertThat(presenceService.find(aliceUuid)).isEmpty();
			assertThat(presenceService.find(bobUuid)).isPresent();
		} finally {
			if (aliceSession.isOpen()) {
				aliceSession.close();
			}
			bobSession.close();
		}
	}
}
