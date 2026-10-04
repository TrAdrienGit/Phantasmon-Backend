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
import org.springframework.web.socket.CloseStatus;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "phantasmon.logging.enabled=false")
class PhantasmonWebSocketIntegrationTest {

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

	private static class RecordingHandler extends TextWebSocketHandler {
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			received.add(message.getPayload());
		}
	}

	private WebSocketSession connect(String token, RecordingHandler handler) throws Exception {
		StandardWebSocketClient client = new StandardWebSocketClient();
		return client.execute(handler, "ws://localhost:" + port + "/ws?token=" + token).get(5, TimeUnit.SECONDS);
	}

	@Test
	void heartbeatReceivesAck() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		try {
			session.sendMessage(new TextMessage("""
					{"type":"Heartbeat","data":{}}
					"""));
			String reply = handler.received.poll(5, TimeUnit.SECONDS);

			assertThat(reply).contains("HeartbeatAck");
		} finally {
			session.close();
		}
	}

	@Test
	void aFloodOfMessagesIsCutOffWithOneError() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();
		WebSocketSession session = connect(jwtService.issueAccessToken(playerUuid, "Bichou"), handler);
		try {
			for (int i = 0; i < 400; i++) {
				session.sendMessage(new TextMessage("{\"type\":\"Heartbeat\",\"data\":{}}"));
			}
			Thread.sleep(1000);
			long acks = handler.received.stream().filter(m -> m.contains("HeartbeatAck")).count();
			long errors = handler.received.stream().filter(m -> m.contains("ERROR_WS_RATE_LIMITED")).count();
			assertThat(acks).isLessThan(400);
			assertThat(errors).isGreaterThanOrEqualTo(1);
		} finally {
			session.close();
		}
	}

	@Test
	void reconnectingReplacesTheOldConnectionWithoutLosingTheNewOne() throws Exception {
		// SEC-8: closing the old connection used to unregister the new one and remove the player's presence.
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		RecordingHandler oldHandler = new RecordingHandler();
		WebSocketSession oldSession = joinGroup(playerUuid, oldHandler, "fp-sec8");
		RecordingHandler newHandler = new RecordingHandler();
		WebSocketSession newSession = joinGroup(playerUuid, newHandler, "fp-sec8");
		try {
			Thread.sleep(500);
			assertThat(oldSession.isOpen()).as("the old connection is closed by the backend").isFalse();
			assertThat(presenceService.find(playerUuid)).isPresent();

			newSession.sendMessage(new TextMessage("{\"type\":\"Heartbeat\",\"data\":{}}"));
			String reply = null;
			for (int i = 0; i < 10 && (reply == null || !reply.contains("HeartbeatAck")); i++) {
				reply = newHandler.received.poll(1, TimeUnit.SECONDS);
			}
			assertThat(reply).contains("HeartbeatAck");
			assertThat(presenceService.find(playerUuid)).isPresent();
		} finally {
			if (oldSession.isOpen()) {
				oldSession.close();
			}
			newSession.close();
		}
	}

	@Test
	void joinServerGroupRecordsPresence() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		try {
			session.sendMessage(new TextMessage("""
					{"type":"JoinServerGroup","data":{"server_fingerprint":"fp-1","dimension":"minecraft:overworld"}}
					"""));
			Thread.sleep(300);

			assertThat(presenceService.find(playerUuid)).isPresent();
			assertThat(presenceService.find(playerUuid).get().serverFingerprint()).isEqualTo("fp-1");
		} finally {
			session.close();
		}
	}

	@Test
	void disconnectRemovesPresence() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		session.sendMessage(new TextMessage("""
				{"type":"JoinServerGroup","data":{"server_fingerprint":"fp-1","dimension":"minecraft:overworld"}}
				"""));
		Thread.sleep(300);
		assertThat(presenceService.find(playerUuid)).isPresent();

		session.close(CloseStatus.NORMAL);
		Thread.sleep(300);

		assertThat(presenceService.find(playerUuid)).isEmpty();
	}

	@Test
	void unknownMessageTypeReturnsError() throws Exception {
		UUID playerUuid = UUID.randomUUID();
		playerService.recordConnection(playerUuid, "Bichou");
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		RecordingHandler handler = new RecordingHandler();

		WebSocketSession session = connect(token, handler);
		try {
			session.sendMessage(new TextMessage("""
					{"type":"NotARealType","data":{}}
					"""));
			String reply = handler.received.poll(5, TimeUnit.SECONDS);

			assertThat(reply).contains("ERROR_WS_UNKNOWN_MESSAGE_TYPE");
		} finally {
			session.close();
		}
	}

	@Test
	void handshakeIsRejectedWithoutAValidToken() {
		StandardWebSocketClient client = new StandardWebSocketClient();
		RecordingHandler handler = new RecordingHandler();

		org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> client.execute(handler, "ws://localhost:" + port + "/ws?token=not-a-real-token")
						.get(5, TimeUnit.SECONDS));
	}

	/** In the active team (team_slot 1) — sendable out as a Ghost (CAD: only team members can be sent out, not PC-only Pokémon). */
	private Pokemon givePokemon(UUID ownerUuid) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), ownerUuid, "pikachu", null,
				(short) 50, "timid", "static", false, null, null, (short) 1, "1.8.1", Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	/** PC-only (no team_slot) — must be rejected by SendOutGhost. */
	private Pokemon givePcOnlyPokemon(UUID ownerUuid) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), ownerUuid, "pikachu", null,
				(short) 50, "timid", "static", false, (short) 1, (short) 1, null, "1.8.1", Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	private WebSocketSession joinGroup(UUID playerUuid, RecordingHandler handler, String fingerprint) throws Exception {
		String token = jwtService.issueAccessToken(playerUuid, "Bichou");
		WebSocketSession session = connect(token, handler);
		session.sendMessage(new TextMessage(
				"{\"type\":\"JoinServerGroup\",\"data\":{\"server_fingerprint\":\"" + fingerprint + "\",\"dimension\":\"minecraft:overworld\"}}"));
		Thread.sleep(300);
		return session;
	}

	@Test
	void aPresenceWithoutFingerprintOrDimensionIsRefusedAndBreaksNoOneElse() throws Exception {
		// SEC-4: a null fingerprint used to make every other player's group lookup throw.
		UUID malloryUuid = UUID.randomUUID();
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(malloryUuid, "Mallory");
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = givePokemon(aliceUuid);

		RecordingHandler malloryHandler = new RecordingHandler();
		WebSocketSession mallorySession = connect(jwtService.issueAccessToken(malloryUuid, "Mallory"), malloryHandler);
		RecordingHandler aliceHandler = new RecordingHandler();
		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-sec4");
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-sec4");

		try {
			mallorySession.sendMessage(new TextMessage("{\"type\":\"JoinServerGroup\",\"data\":{\"dimension\":\"minecraft:overworld\"}}"));
			assertThat(malloryHandler.received.poll(5, TimeUnit.SECONDS)).contains("ERROR_WS_MALFORMED_MESSAGE");
			mallorySession.sendMessage(new TextMessage("{\"type\":\"JoinServerGroup\",\"data\":{\"server_fingerprint\":\"fp-sec4\"}}"));
			assertThat(malloryHandler.received.poll(5, TimeUnit.SECONDS)).contains("ERROR_WS_MALFORMED_MESSAGE");
			assertThat(presenceService.find(malloryUuid)).isEmpty();

			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));
			assertThat(bobHandler.received.poll(5, TimeUnit.SECONDS)).contains("GhostEntitySpawn");
		} finally {
			mallorySession.close();
			aliceSession.close();
			bobSession.close();
		}
	}

	@Test
	void aPositionUpdateWithoutDimensionIsRefused() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		RecordingHandler aliceHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-sec4-pos");
		try {
			aliceSession.sendMessage(new TextMessage("{\"type\":\"PositionUpdate\",\"data\":{\"x\":1,\"y\":2,\"z\":3}}"));
			assertThat(aliceHandler.received.poll(5, TimeUnit.SECONDS)).contains("ERROR_WS_MALFORMED_MESSAGE");
			assertThat(presenceService.find(aliceUuid).orElseThrow().dimension()).isEqualTo("minecraft:overworld");
		} finally {
			aliceSession.close();
		}
	}

	@Test
	void sendOutGhostBroadcastsSpawnToGroupMembers() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = givePokemon(aliceUuid);

		RecordingHandler aliceHandler = new RecordingHandler();
		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-1");
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-ghost-1");

		try {
			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));

			String reply = bobHandler.received.poll(5, TimeUnit.SECONDS);
			assertThat(reply).contains("GhostEntitySpawn").contains(aliceUuid.toString()).contains(aliceMon.getUuid().toString())
					.contains("\"species\":\"pikachu\"").contains("\"level\":50");

			String selfReply = aliceHandler.received.poll(5, TimeUnit.SECONDS);
			assertThat(selfReply).as("the owner must also see their own Ghost, not just other group members")
					.contains("GhostEntitySpawn").contains(aliceMon.getUuid().toString());
		} finally {
			aliceSession.close();
			bobSession.close();
		}
	}

	@Test
	void ghostSpawnCarriesTheStoredGenderSoTheModelCanBeGenderSpecific() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), aliceUuid, "meowstic", null,
				(short) 50, "timid", "keen-eye", false, null, null, (short) 1, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of(), "gender", "F")));

		RecordingHandler aliceHandler = new RecordingHandler();
		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-gender");
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-ghost-gender");

		try {
			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));

			String reply = bobHandler.received.poll(5, TimeUnit.SECONDS);
			assertThat(reply).contains("GhostEntitySpawn").contains("\"gender\":\"F\"");
		} finally {
			aliceSession.close();
			bobSession.close();
		}
	}

	@Test
	void ghostSpawnCarriesTheNicknameForTheGhostIndicator() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), aliceUuid, "pikachu", null,
				(short) 50, "timid", "static", false, null, null, (short) 1, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of(), "nickname", "Bichou")));

		RecordingHandler aliceHandler = new RecordingHandler();
		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-nickname");
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-ghost-nickname");

		try {
			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));

			String reply = bobHandler.received.poll(5, TimeUnit.SECONDS);
			assertThat(reply).contains("GhostEntitySpawn").contains("\"nickname\":\"Bichou\"");
		} finally {
			aliceSession.close();
			bobSession.close();
		}
	}

	@Test
	void sendOutGhostWithUnownedPokemonIsRejected() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon bobMon = givePokemon(bobUuid);

		RecordingHandler aliceHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-2");

		try {
			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + bobMon.getUuid() + "\"}}"));

			String reply = aliceHandler.received.poll(5, TimeUnit.SECONDS);
			assertThat(reply).contains("ERROR_OWNERSHIP_MISMATCH");
		} finally {
			aliceSession.close();
		}
	}

	@Test
	void sendOutGhostWithPcOnlyPokemonIsRejected() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		Pokemon pcMon = givePcOnlyPokemon(aliceUuid);

		RecordingHandler aliceHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-6");

		try {
			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + pcMon.getUuid() + "\"}}"));

			String reply = aliceHandler.received.poll(5, TimeUnit.SECONDS);
			assertThat(reply).contains("ERROR_POKEMON_NOT_IN_TEAM");
		} finally {
			aliceSession.close();
		}
	}

	@Test
	void recallGhostBroadcastsDespawnToGroupMembers() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = givePokemon(aliceUuid);

		RecordingHandler aliceHandler = new RecordingHandler();
		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-3");
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-ghost-3");

		try {
			aliceSession.sendMessage(new TextMessage(
					"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));
			bobHandler.received.poll(5, TimeUnit.SECONDS);
			aliceHandler.received.poll(5, TimeUnit.SECONDS); // drain Alice's own spawn echo

			aliceSession.sendMessage(new TextMessage("{\"type\":\"RecallGhost\",\"data\":{}}"));
			String reply = bobHandler.received.poll(5, TimeUnit.SECONDS);
			String selfReply = aliceHandler.received.poll(5, TimeUnit.SECONDS);

			assertThat(reply).contains("GhostEntityDespawn").contains(aliceMon.getUuid().toString());
			assertThat(selfReply).as("the owner must also see their own Ghost despawn")
					.contains("GhostEntityDespawn").contains(aliceMon.getUuid().toString());
		} finally {
			aliceSession.close();
			bobSession.close();
		}
	}

	@Test
	void joiningGroupSendsCatchUpForAlreadyOutGhosts() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = givePokemon(aliceUuid);

		RecordingHandler aliceHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-4");
		aliceSession.sendMessage(new TextMessage(
				"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));
		Thread.sleep(300);

		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-ghost-4");

		try {
			String reply = bobHandler.received.poll(5, TimeUnit.SECONDS);
			assertThat(reply).contains("GhostEntitySpawn").contains(aliceMon.getUuid().toString());
		} finally {
			aliceSession.close();
			bobSession.close();
		}
	}

	@Test
	void disconnectWhileGhostIsOutBroadcastsDespawn() throws Exception {
		UUID aliceUuid = UUID.randomUUID();
		UUID bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		Pokemon aliceMon = givePokemon(aliceUuid);

		RecordingHandler aliceHandler = new RecordingHandler();
		RecordingHandler bobHandler = new RecordingHandler();
		WebSocketSession aliceSession = joinGroup(aliceUuid, aliceHandler, "fp-ghost-5");
		WebSocketSession bobSession = joinGroup(bobUuid, bobHandler, "fp-ghost-5");

		aliceSession.sendMessage(new TextMessage(
				"{\"type\":\"SendOutGhost\",\"data\":{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}}"));
		bobHandler.received.poll(5, TimeUnit.SECONDS);

		aliceSession.close(CloseStatus.NORMAL);
		String reply = bobHandler.received.poll(5, TimeUnit.SECONDS);

		assertThat(reply).contains("GhostEntityDespawn").contains(aliceMon.getUuid().toString());
		bobSession.close();
	}
}
