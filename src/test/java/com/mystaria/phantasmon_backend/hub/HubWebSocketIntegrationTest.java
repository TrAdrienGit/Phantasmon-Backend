package com.mystaria.phantasmon_backend.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import com.mystaria.phantasmon_backend.websocket.PhantasmonWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phantasmon Network, step N2 — the Global Hub over real WebSocket connections (network-cahier-des-charges.md §5.3 to
 * §5.8). Anchors are shared within a server, never across servers (D-30). Two Minecraft servers are simulated by two fingerprints; the Hub holds 2 players here so the capacity rule is
 * testable with three clients.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"phantasmon.logging.enabled=false", "phantasmon.hub.capacity=2" })
class HubWebSocketIntegrationTest {

	private static final String OVERWORLD = "minecraft:overworld";

	@LocalServerPort
	private int port;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private HubAnchorService hubAnchorService;

	@Autowired
	private PhantasmonWebSocketHandler webSocketHandler;

	@Autowired
	private PokemonRepository pokemonRepository;

	@Autowired
	private ObjectMapper objectMapper;

	private final List<Client> clients = new ArrayList<>();

	/** One connected player and everything the backend sent them. */
	private final class Client extends TextWebSocketHandler {
		final UUID uuid = UUID.randomUUID();
		final String name;
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();
		WebSocketSession session;

		Client(String name) {
			this.name = name;
		}

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			received.add(message.getPayload());
		}

		void send(String type, String data) throws Exception {
			session.sendMessage(new TextMessage("{\"type\":\"" + type + "\",\"data\":" + data + "}"));
		}

		/** First message of this type, skipping the others; fails after 5 seconds. */
		JsonNode await(String type) throws Exception {
			long deadline = System.currentTimeMillis() + 5000;
			while (System.currentTimeMillis() < deadline) {
				String message = received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
				if (message != null) {
					JsonNode node = objectMapper.readTree(message);
					if (type.equals(node.get("type").asString())) {
						return node.get("data");
					}
				}
			}
			throw new AssertionError(name + " never received " + type);
		}

		/** The error code of the next {@code Error} message. */
		String awaitError() throws Exception {
			return await("Error").get("error_code").asString();
		}

		/** Asserts no message of this type arrives within half a second. */
		void expectNo(String type) throws Exception {
			long deadline = System.currentTimeMillis() + 500;
			while (System.currentTimeMillis() < deadline) {
				String message = received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
				if (message != null) {
					assertThat(objectMapper.readTree(message).get("type").asString()).as(name + " got " + message).isNotEqualTo(type);
				}
			}
		}

		void joinServer(String fingerprint) throws Exception {
			send("JoinServerGroup", "{\"server_fingerprint\":\"" + fingerprint + "\",\"dimension\":\"" + OVERWORLD + "\"}");
		}

		void joinHub(UUID anchorUuid) throws Exception {
			send("HubJoin", "{\"anchor_uuid\":\"" + anchorUuid + "\"}");
		}
	}

	private Client connect(String prefix) throws Exception {
		Client client = new Client(prefix);
		String name = prefix + client.uuid.toString().substring(0, 6);
		playerService.recordConnection(client.uuid, name);
		client.session = new StandardWebSocketClient().execute(client,
				"ws://localhost:" + port + "/ws?token=" + jwtService.issueAccessToken(client.uuid, name)).get(5, TimeUnit.SECONDS);
		clients.add(client);
		return client;
	}

	/** A connected player standing in a fresh anchor of their own on {@code fingerprint}. */
	private Client inAnchor(String prefix, String fingerprint) throws Exception {
		Client client = connect(prefix);
		UUID anchor = anchorOf(client, fingerprint);
		client.joinServer(fingerprint);
		client.joinHub(anchor);
		return client;
	}

	private UUID anchorOf(Client owner, String fingerprint) {
		return hubAnchorService.create(owner.uuid, new HubAnchorCreateRequest(UUID.randomUUID(),
				"Anchor " + owner.uuid.toString().substring(0, 8), fingerprint, OVERWORLD,
				new HubAnchorCreateRequest.Origin(0.0, 64.0, 0.0), 0.0)).uuid();
	}

	private static String server() {
		return "fp-" + UUID.randomUUID();
	}

	@AfterEach
	void closeClients() throws Exception {
		for (Client client : clients) {
			if (client.session.isOpen()) {
				client.session.close();
			}
		}
		Thread.sleep(300); // let the backend process the closes before the next test fills the Hub again
	}

	@Test
	void playersOnTwoServersSeeEachOtherEnterMoveAndLeave() throws Exception {
		Client alice = inAnchor("Al", server());
		assertThat(alice.await("HubJoined").get("members")).isEmpty();

		Client bob = inAnchor("Bo", server());
		JsonNode bobJoined = bob.await("HubJoined").get("members");
		assertThat(bobJoined).hasSize(1);
		assertThat(bobJoined.get(0).get("player_uuid").asString()).isEqualTo(alice.uuid.toString());
		assertThat(bobJoined.get(0).get("username").asString()).startsWith("Al");
		JsonNode enter = alice.await("HubPlayerEnter");
		assertThat(enter.get("player_uuid").asString()).isEqualTo(bob.uuid.toString());
		assertThat(enter.get("username").asString()).startsWith("Bo");

		bob.send("HubMove", """
				{"x":3.5,"z":-2.0,"y_offset":0.4,"yaw":45,"head_yaw":50,"pitch":10,"pose":"CROUCHING","on_ground":false,
				 "skin_parts":95}""");
		JsonNode move = alice.await("HubPlayerMove");
		assertThat(move.get("player_uuid").asString()).isEqualTo(bob.uuid.toString());
		JsonNode state = move.get("state");
		assertThat(state.get("x").asDouble()).isEqualTo(3.5);
		assertThat(state.get("z").asDouble()).isEqualTo(-2.0);
		assertThat(state.get("y_offset").asDouble()).isEqualTo(0.4);
		assertThat(state.get("yaw").asDouble()).isEqualTo(45);
		assertThat(state.get("head_yaw").asDouble()).isEqualTo(50);
		assertThat(state.get("pitch").asDouble()).isEqualTo(10);
		assertThat(state.get("pose").asString()).isEqualTo("CROUCHING");
		assertThat(state.get("on_ground").asBoolean()).isFalse();
		assertThat(state.get("skin_parts").asInt()).as("hat, jacket, sleeves... shown by the remote player").isEqualTo(95);

		bob.send("HubLeave", "{}");
		assertThat(bob.await("HubLeft").get("reason").asString()).isEqualTo("LEFT");
		assertThat(alice.await("HubPlayerLeave").get("player_uuid").asString()).isEqualTo(bob.uuid.toString());
	}

	@Test
	void aLateJoinerSeesWhereTheOthersStand() throws Exception {
		Client alice = inAnchor("Al", server());
		alice.await("HubJoined");
		alice.send("HubMove", """
				{"x":-4,"z":7,"y_offset":0,"yaw":180,"head_yaw":170,"pitch":0,"pose":"STANDING","on_ground":true}""");
		Thread.sleep(200);

		Client bob = inAnchor("Bo", server());
		JsonNode state = bob.await("HubJoined").get("members").get(0).get("state");
		assertThat(state.get("x").asDouble()).isEqualTo(-4);
		assertThat(state.get("z").asDouble()).isEqualTo(7);
		assertThat(state.get("yaw").asDouble()).isEqualTo(180);
	}

	@Test
	void playersOfTheSameServerShareItsAnchorsAndAreSentEachOther() throws Exception {
		// J1 and J2 on S1: J2 enters through J1's anchor. The backend sends every member to every other one; a client
		// seeing the player for real where the avatar would stand hides the avatar itself.
		String server = server();
		Client alice = inAnchor("Al", server);
		alice.await("HubJoined");
		Client carol = connect("Ca");
		carol.joinServer(server);
		carol.joinHub(hubAnchorService.findMine(alice.uuid).uuid());

		JsonNode members = carol.await("HubJoined").get("members");
		assertThat(members).hasSize(1);
		assertThat(members.get(0).get("player_uuid").asString()).isEqualTo(alice.uuid.toString());
		assertThat(alice.await("HubPlayerEnter").get("player_uuid").asString()).isEqualTo(carol.uuid.toString());

		carol.send("HubMove", """
				{"x":1,"z":1,"y_offset":0,"yaw":0,"head_yaw":0,"pitch":0,"pose":"STANDING","on_ground":true}""");
		assertThat(alice.await("HubPlayerMove").get("player_uuid").asString()).isEqualTo(carol.uuid.toString());

		carol.send("HubChat", "{\"message\":\"  Salut §ctout le monde  \"}");
		JsonNode chat = alice.await("HubChatMessage");
		assertThat(chat.get("player_uuid").asString()).isEqualTo(carol.uuid.toString());
		assertThat(chat.get("username").asString()).startsWith("Ca");
		assertThat(chat.get("message").asString()).isEqualTo("Salut tout le monde");
		assertThat(chat.get("sent_at").asString()).isNotEmpty();
		assertThat(carol.await("HubChatMessage").get("message").asString()).isEqualTo("Salut tout le monde");
	}

	@Test
	void anAnchorIsUsableOnlyFromItsOwnServerAndDimension() throws Exception {
		Client alice = connect("Al");
		String server = server();
		UUID anchor = anchorOf(alice, server);

		alice.joinHub(anchor);
		assertThat(alice.awaitError()).as("no server group yet").isEqualTo("ERROR_HUB_ANCHOR_WRONG_SERVER");

		// J3 on S2 cannot use S1's anchor, even knowing its UUID.
		Client dave = connect("Da");
		dave.joinServer(server());
		dave.joinHub(anchor);
		assertThat(dave.awaitError()).isEqualTo("ERROR_HUB_ANCHOR_WRONG_SERVER");

		dave.joinHub(UUID.randomUUID());
		assertThat(dave.awaitError()).isEqualTo("ERROR_HUB_ANCHOR_NOT_FOUND");

		dave.send("HubJoin", "{}");
		assertThat(dave.awaitError()).isEqualTo("ERROR_WS_MALFORMED_MESSAGE");
	}

	@Test
	void movesAndMessagesAreChecked() throws Exception {
		Client alice = connect("Al");
		alice.send("HubMove", "{\"x\":0,\"z\":0,\"y_offset\":0,\"yaw\":0,\"head_yaw\":0,\"pitch\":0,\"pose\":\"STANDING\",\"on_ground\":true}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_HUB_NOT_JOINED");
		alice.send("HubChat", "{\"message\":\"hello\"}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_HUB_NOT_JOINED");

		String server = server();
		UUID anchor = anchorOf(alice, server);
		alice.joinServer(server);
		alice.joinHub(anchor);
		alice.await("HubJoined");

		alice.send("HubMove", "{\"x\":10.6,\"z\":0,\"y_offset\":0,\"yaw\":0,\"head_yaw\":0,\"pitch\":0,\"pose\":\"STANDING\",\"on_ground\":true}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_HUB_OUT_OF_BOUNDS");
		alice.send("HubMove", "{\"x\":0,\"z\":0,\"y_offset\":-1,\"yaw\":0,\"head_yaw\":0,\"pitch\":0,\"pose\":\"STANDING\",\"on_ground\":true}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_HUB_OUT_OF_BOUNDS");
		alice.send("HubMove", "{\"x\":0,\"z\":0,\"y_offset\":0,\"yaw\":0,\"head_yaw\":0,\"pitch\":0,\"pose\":\"DANCING\",\"on_ground\":true}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_WS_MALFORMED_MESSAGE");
		alice.send("HubMove", "{\"x\":0,\"z\":0}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_WS_MALFORMED_MESSAGE");
		alice.send("HubMove", "{\"x\":0,\"z\":0,\"y_offset\":0,\"yaw\":0,\"head_yaw\":0,\"pitch\":0,\"pose\":\"STANDING\",\"on_ground\":true,\"skin_parts\":128}");
		assertThat(alice.awaitError()).as("skin_parts is a 7-bit mask").isEqualTo("ERROR_WS_MALFORMED_MESSAGE");

		alice.send("HubChat", "{\"message\":\"" + "a".repeat(257) + "\"}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_HUB_CHAT_TOO_LONG");
		alice.send("HubChat", "{\"message\":\"   \"}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_WS_MALFORMED_MESSAGE");
		alice.send("HubChat", "{\"message\":\"first\"}");
		alice.await("HubChatMessage");
		alice.send("HubChat", "{\"message\":\"too soon\"}");
		assertThat(alice.awaitError()).isEqualTo("ERROR_HUB_CHAT_RATE_LIMITED");
	}

	/** A Pokémon in the first slot of {@code owner}'s team, ready to be sent out as a Ghost. */
	private Pokemon teamMon(Client owner, String species) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), owner.uuid, species, null, (short) 42,
				"timid", "static", true, null, null, (short) 1, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of(), "nickname", "Sparky", "gender", "F")));
	}

	@Test
	void membersGhostsAreSharedThroughTheHubWithoutRealCoordinates() throws Exception {
		Client alice = inAnchor("Al", server());
		alice.await("HubJoined");
		Client bob = inAnchor("Bo", server());
		bob.await("HubJoined");
		alice.await("HubPlayerEnter");

		// Alice sends her Ghost out while in the Hub: Bob, on another server, gets it — never her real position.
		Pokemon pikachu = teamMon(alice, "pikachu");
		alice.send("PositionUpdate", "{\"x\":1234.5,\"y\":70,\"z\":-987.5,\"dimension\":\"" + OVERWORLD + "\"}");
		alice.send("SendOutGhost", "{\"pokemon_uuid\":\"" + pikachu.getUuid() + "\"}");
		JsonNode spawn = bob.await("HubGhostSpawn");
		assertThat(spawn.get("player_uuid").asString()).isEqualTo(alice.uuid.toString());
		assertThat(spawn.get("pokemon_uuid").asString()).isEqualTo(pikachu.getUuid().toString());
		assertThat(spawn.get("species").asString()).isEqualTo("pikachu");
		assertThat(spawn.get("is_shiny").asBoolean()).isTrue();
		assertThat(spawn.get("level").asInt()).isEqualTo(42);
		assertThat(spawn.get("gender").asString()).isEqualTo("F");
		assertThat(spawn.get("nickname").asString()).isEqualTo("Sparky");
		assertThat(spawn.has("position")).as("real coordinates never reach the Hub").isFalse();
		alice.expectNo("HubGhostSpawn");

		// Coming back later (the Hub holds 2 here), Bob finds Alice's Ghost in HubJoined.
		bob.send("HubLeave", "{}");
		bob.await("HubLeft");
		bob.joinHub(hubAnchorService.findMine(bob.uuid).uuid());
		JsonNode aliceEntry = bob.await("HubJoined").get("members").get(0);
		assertThat(aliceEntry.get("player_uuid").asString()).isEqualTo(alice.uuid.toString());
		assertThat(aliceEntry.get("ghost").get("species").asString()).isEqualTo("pikachu");
		assertThat(aliceEntry.get("ghost").has("position")).isFalse();

		// Recalled: gone for every other member.
		alice.send("RecallGhost", "{}");
		JsonNode despawn = bob.await("HubGhostDespawn");
		assertThat(despawn.get("player_uuid").asString()).isEqualTo(alice.uuid.toString());
		assertThat(despawn.get("pokemon_uuid").asString()).isEqualTo(pikachu.getUuid().toString());
	}

	@Test
	void aGhostAlreadyOutEntersTheHubWithItsOwner() throws Exception {
		Client alice = inAnchor("Al", server());
		alice.await("HubJoined");

		Client bob = connect("Bo");
		String server = server();
		UUID anchor = anchorOf(bob, server);
		bob.joinServer(server);
		Pokemon eevee = teamMon(bob, "eevee");
		bob.send("SendOutGhost", "{\"pokemon_uuid\":\"" + eevee.getUuid() + "\"}");
		bob.await("GhostEntitySpawn");
		bob.joinHub(anchor);

		assertThat(alice.await("HubPlayerEnter").get("player_uuid").asString()).isEqualTo(bob.uuid.toString());
		assertThat(alice.await("HubGhostSpawn").get("species").asString()).isEqualTo("eevee");
	}

	@Test
	void playersOfTwoServersMetInTheHubCanInviteEachOtherToTradeAndBattle() throws Exception {
		// Milestone 2: live trade and battle invites never depended on the server group, only on the target being
		// connected — what lets the wheel opened on an avatar invite a player of another Minecraft server.
		Client alice = inAnchor("Al", server());
		alice.await("HubJoined");
		Client bob = inAnchor("Bo", server());
		bob.await("HubJoined");

		alice.send("TradeInvite", "{\"target_uuid\":\"" + bob.uuid + "\"}");
		JsonNode tradeInvite = bob.await("TradeInviteReceived");
		assertThat(tradeInvite.get("from_uuid").asString()).isEqualTo(alice.uuid.toString());
		assertThat(tradeInvite.get("from_name").asString()).startsWith("Al");

		alice.send("BattleInvite", "{\"target_uuid\":\"" + bob.uuid + "\",\"team\":\"GHOST\"}");
		assertThat(bob.await("BattleInviteReceived").get("from_uuid").asString()).isEqualTo(alice.uuid.toString());
	}

	@Test
	void theHubRefusesPlayersBeyondItsCapacity() throws Exception {
		inAnchor("Al", server()).await("HubJoined");
		inAnchor("Bo", server()).await("HubJoined");

		Client carol = inAnchor("Ca", server());
		JsonNode error = carol.await("Error");
		assertThat(error.get("error_code").asString()).isEqualTo("ERROR_HUB_FULL");
		assertThat(error.get("details").get("capacity").asInt()).isEqualTo(2);
	}

	@Test
	void disconnectingChangingServerOrLosingTheAnchorTakesThePlayerOutOfTheHub() throws Exception {
		Client alice = inAnchor("Al", server());
		alice.await("HubJoined");
		Client bob = inAnchor("Bo", server());
		bob.await("HubJoined");
		alice.await("HubPlayerEnter");

		// Bob's client switches to another server (or dimension): his anchor no longer applies.
		bob.joinServer(server());
		assertThat(bob.await("HubLeft").get("reason").asString()).isEqualTo("SERVER_CHANGED");
		assertThat(alice.await("HubPlayerLeave").get("player_uuid").asString()).isEqualTo(bob.uuid.toString());

		// Alice's anchor is deleted while she stands in the Hub through it.
		Client dave = inAnchor("Da", server());
		dave.await("HubJoined");
		alice.await("HubPlayerEnter");
		hubAnchorService.delete(alice.uuid, hubAnchorService.findMine(alice.uuid).uuid());
		assertThat(alice.await("HubLeft").get("reason").asString()).isEqualTo("ANCHOR_DELETED");
		assertThat(dave.await("HubPlayerLeave").get("player_uuid").asString()).isEqualTo(alice.uuid.toString());

		// Dave's heartbeat expires (TTL sweep), then a newcomer finds the Hub empty.
		webSocketHandler.expire(dave.uuid);
		Client erin = inAnchor("Er", server());
		assertThat(erin.await("HubJoined").get("members")).isEmpty();

		// A plain disconnection leaves the Hub too.
		Client fred = inAnchor("Fr", server());
		fred.await("HubJoined");
		erin.await("HubPlayerEnter");
		fred.session.close();
		assertThat(erin.await("HubPlayerLeave").get("player_uuid").asString()).isEqualTo(fred.uuid.toString());
	}
}
