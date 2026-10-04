package com.mystaria.phantasmon_backend.battle;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live Ghost battles over the presence WebSocket (Phase 9): invitation, host
 * selection with alternation, relay between host and guest, timer, result
 * guardrails, forfeit and disconnection. Real Tomcat + real WebSocket clients,
 * same approach as the live trade tests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "phantasmon.logging.enabled=false")
class LiveBattleWebSocketIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private PokemonRepository pokemonRepository;

	@Autowired
	private BattleRepository battleRepository;

	@Autowired
	private LiveBattleService liveBattleService;

	@Autowired
	private ObjectMapper objectMapper;

	private UUID aliceUuid;
	private UUID bobUuid;
	private RecordingHandler alice;
	private RecordingHandler bob;
	private WebSocketSession aliceSession;
	private WebSocketSession bobSession;

	private static class RecordingHandler extends TextWebSocketHandler {
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			received.add(message.getPayload());
		}
	}

	@BeforeEach
	void connectBothPlayers() throws Exception {
		aliceUuid = UUID.randomUUID();
		bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		alice = new RecordingHandler();
		bob = new RecordingHandler();
		aliceSession = connect(aliceUuid, "Alice", alice);
		bobSession = connect(bobUuid, "Bob", bob);
	}

	@AfterEach
	void closeSessions() throws Exception {
		if (aliceSession.isOpen()) {
			aliceSession.close();
		}
		if (bobSession.isOpen()) {
			bobSession.close();
		}
	}

	private WebSocketSession connect(UUID playerUuid, String name, RecordingHandler handler) throws Exception {
		String token = jwtService.issueAccessToken(playerUuid, name);
		// The test client's own container must also accept big frames (the relay test sends 100 KB).
		jakarta.websocket.WebSocketContainer container = jakarta.websocket.ContainerProvider.getWebSocketContainer();
		container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
		return new StandardWebSocketClient(container)
				.execute(handler, "ws://localhost:" + port + "/ws?token=" + token)
				.get(5, TimeUnit.SECONDS);
	}

	private Pokemon teamMember(UUID ownerUuid, String species, int teamSlot) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), ownerUuid, species, null,
				(short) 50, "timid", "static", false, null, null, (short) teamSlot, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	private static void send(WebSocketSession session, String type, String dataJson) throws Exception {
		session.sendMessage(new TextMessage("{\"type\":\"" + type + "\",\"data\":" + dataJson + "}"));
	}

	private JsonNode await(RecordingHandler handler, String type) throws Exception {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			String raw = handler.received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			if (raw == null) {
				break;
			}
			JsonNode node = objectMapper.readTree(raw);
			if (type.equals(node.get("type").asString())) {
				return node.get("data");
			}
		}
		throw new AssertionError("No '" + type + "' message received within 5s");
	}

	private void assertNothing(RecordingHandler handler, String type) throws Exception {
		long deadline = System.currentTimeMillis() + 600;
		while (System.currentTimeMillis() < deadline) {
			String raw = handler.received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
			if (raw != null && type.equals(objectMapper.readTree(raw).get("type").asString())) {
				throw new AssertionError("Unexpected '" + type + "': " + raw);
			}
		}
	}

	/** The inviter invites, the invitee accepts: both land in the lobby. Returns the inviter's first lobby view. */
	private JsonNode openLobby(WebSocketSession inviter, RecordingHandler inviterHandler, UUID targetUuid,
			WebSocketSession invitee, RecordingHandler inviteeHandler) throws Exception {
		send(inviter, "BattleInvite", "{\"target_uuid\":\"" + targetUuid + "\"}");
		JsonNode invite = await(inviteeHandler, "BattleInviteReceived");
		send(invitee, "BattleInviteResponse",
				"{\"invite_uuid\":\"" + invite.get("invite_uuid").asString() + "\",\"accept\":true}");
		await(inviteeHandler, "BattleLobbyUpdated");
		return await(inviterHandler, "BattleLobbyUpdated");
	}

	private static void setReady(WebSocketSession session, String lobby, boolean ready) throws Exception {
		send(session, "BattleLobbySetReady", "{\"lobby_uuid\":\"" + lobby + "\",\"ready\":" + ready + "}");
	}

	private static void setLead(WebSocketSession session, String lobby, int index) throws Exception {
		send(session, "BattleLobbySetLead", "{\"lobby_uuid\":\"" + lobby + "\",\"index\":" + index + "}");
	}

	/** Both players get ready in the lobby: the battle starts. */
	private void readyBoth(String lobby) throws Exception {
		setReady(aliceSession, lobby, true);
		setReady(bobSession, lobby, true);
	}

	/** Invite, accept, both ready with their default lead; returns the inviter's {@code BattleSessionStarted}. */
	private JsonNode startBattle(WebSocketSession inviter, RecordingHandler inviterHandler, UUID targetUuid,
			WebSocketSession invitee, RecordingHandler inviteeHandler) throws Exception {
		readyBoth(openLobby(inviter, inviterHandler, targetUuid, invitee, inviteeHandler).get("lobby_uuid").asString());
		await(inviteeHandler, "BattleSessionStarted");
		return await(inviterHandler, "BattleSessionStarted");
	}

	private static void joinGroup(WebSocketSession session, String fingerprint) throws Exception {
		send(session, "JoinServerGroup", "{\"server_fingerprint\":\"" + fingerprint + "\",\"dimension\":\"minecraft:overworld\"}");
		Thread.sleep(300);
	}

	@Test
	void openingTheLobbyRecallsBothPlayersGhosts() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		Pokemon bobMon = teamMember(bobUuid, "charmander", 1);
		String group = "fp-battle-recall-" + UUID.randomUUID();
		joinGroup(aliceSession, group);
		joinGroup(bobSession, group);
		send(aliceSession, "SendOutGhost", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");
		send(bobSession, "SendOutGhost", "{\"pokemon_uuid\":\"" + bobMon.getUuid() + "\"}");
		Thread.sleep(300);

		openLobby(aliceSession, alice, bobUuid, bobSession, bob);

		// Each player sees both Ghosts go: their own and the opponent's (same group).
		java.util.Set<String> seenByAlice = new java.util.HashSet<>();
		seenByAlice.add(await(alice, "GhostEntityDespawn").get("pokemon_uuid").asString());
		seenByAlice.add(await(alice, "GhostEntityDespawn").get("pokemon_uuid").asString());
		assertThat(seenByAlice).containsExactlyInAnyOrder(aliceMon.getUuid().toString(), bobMon.getUuid().toString());
		java.util.Set<String> seenByBob = new java.util.HashSet<>();
		seenByBob.add(await(bob, "GhostEntityDespawn").get("pokemon_uuid").asString());
		seenByBob.add(await(bob, "GhostEntityDespawn").get("pokemon_uuid").asString());
		assertThat(seenByBob).containsExactlyInAnyOrder(aliceMon.getUuid().toString(), bobMon.getUuid().toString());
	}

	@Test
	void noGhostCanBeSentOutWhileTheBattleLasts() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String group = "fp-battle-block-" + UUID.randomUUID();
		joinGroup(aliceSession, group);
		joinGroup(bobSession, group);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(aliceSession, "SendOutGhost", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");
		assertThat(await(alice, "Error").get("error_code").asString()).isEqualTo("ERROR_GHOST_IN_BATTLE");
		assertNothing(bob, "GhostEntitySpawn");

		send(aliceSession, "BattleLeave", "{\"battle_uuid\":\"" + battle + "\"}");
		await(alice, "BattleEnded");
		send(aliceSession, "SendOutGhost", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");
		assertThat(await(bob, "GhostEntitySpawn").get("pokemon_uuid").asString())
				.as("once the battle is over, Ghosts can go out again").isEqualTo(aliceMon.getUuid().toString());
	}

	/** A copy of one real Cobblemon party member, as the client sends it (same shape as a Ghost Pokémon). */
	private static String partyMember(UUID uuid, String species, String ivsJson) {
		return """
				{"uuid":"%s","species":"%s","form":null,"level":62,"nature":"jolly","ability":"roughskin","is_shiny":false,
				 "cobblemon_data_version":"1.8.1",
				 "data":{"ivs":%s,"evs":{"atk":252,"spe":252},"moves":["earthquake","dragonclaw"],"held_item":"choice_scarf"}}
				""".formatted(uuid, species, ivsJson);
	}

	@Test
	void theInviterCanFightWithACopyOfTheirCobblemonParty() throws Exception {
		// Ghost vs normal Pokémon (CAD Partie 1 §31): the real party is only copied, never touched.
		Pokemon bobMon = teamMember(bobUuid, "charmander", 1);
		UUID realUuid = UUID.randomUUID();
		send(aliceSession, "BattleInvite", "{\"target_uuid\":\"" + bobUuid + "\",\"team\":\"COBBLEMON\",\"party\":["
				+ partyMember(realUuid, "garchomp", "{\"hp\":31}") + "]}");
		JsonNode invite = await(bob, "BattleInviteReceived");
		assertThat(invite.get("from_team").asString()).isEqualTo("COBBLEMON");
		send(bobSession, "BattleInviteResponse", "{\"invite_uuid\":\"" + invite.get("invite_uuid").asString() + "\",\"accept\":true}");
		readyBoth(await(alice, "BattleLobbyUpdated").get("lobby_uuid").asString());

		JsonNode aliceView = await(alice, "BattleSessionStarted");
		assertThat(aliceView.get("role").asString()).isEqualTo("HOST");
		assertThat(aliceView.get("own_team_source").asString()).isEqualTo("COBBLEMON");
		assertThat(aliceView.get("opponent_team_source").asString()).isEqualTo("GHOST");
		JsonNode real = aliceView.get("own_team").get(0);
		assertThat(real.get("uuid").asString()).isEqualTo(realUuid.toString());
		assertThat(real.get("species").asString()).isEqualTo("garchomp");
		assertThat(real.get("data").get("held_item").asString()).isEqualTo("choice_scarf");
		assertThat(aliceView.get("opponent_team").get(0).get("uuid").asString()).isEqualTo(bobMon.getUuid().toString());

		BattleSession stored = battleRepository.findById(UUID.fromString(aliceView.get("battle_uuid").asString())).orElseThrow();
		assertThat(stored.getTeamA()).containsExactly(realUuid);
	}

	@Test
	void theInviteeCanAnswerWithTheirCobblemonParty() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		UUID realUuid = UUID.randomUUID();
		send(aliceSession, "BattleInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");
		JsonNode invite = await(bob, "BattleInviteReceived");
		assertThat(invite.get("from_team").asString()).as("Ghosts unless said otherwise").isEqualTo("GHOST");
		send(bobSession, "BattleInviteResponse", "{\"invite_uuid\":\"" + invite.get("invite_uuid").asString()
				+ "\",\"accept\":true,\"team\":\"COBBLEMON\",\"party\":[" + partyMember(realUuid, "lucario", "{}") + "]}");
		readyBoth(await(alice, "BattleLobbyUpdated").get("lobby_uuid").asString());

		JsonNode aliceView = await(alice, "BattleSessionStarted");
		assertThat(aliceView.get("opponent_team_source").asString()).isEqualTo("COBBLEMON");
		assertThat(aliceView.get("opponent_team").get(0).get("species").asString()).isEqualTo("lucario");
		JsonNode bobView = await(bob, "BattleSessionStarted");
		assertThat(bobView.get("own_team_source").asString()).isEqualTo("COBBLEMON");
		assertThat(bobView.get("own_team").get(0).get("uuid").asString()).isEqualTo(realUuid.toString());
	}

	@Test
	void anIllegalOrMalformedPartyIsRefused() throws Exception {
		teamMember(bobUuid, "charmander", 1);
		for (String party : List.of(
				"[" + partyMember(UUID.randomUUID(), "garchomp", "{\"hp\":40}") + "]",
				"[]",
				"[" + String.join(",", java.util.Collections.nCopies(7, partyMember(UUID.randomUUID(), "garchomp", "{}"))) + "]")) {
			send(aliceSession, "BattleInvite", "{\"target_uuid\":\"" + bobUuid + "\",\"team\":\"COBBLEMON\",\"party\":" + party + "}");
			assertThat(await(alice, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_INVALID_PARTY");
		}
		assertNothing(bob, "BattleInviteReceived");
	}

	@Test
	void inviteReachesTheTarget() throws Exception {
		send(aliceSession, "BattleInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");

		JsonNode received = await(bob, "BattleInviteReceived");
		assertThat(received.get("from_name").asString()).isEqualTo("Alice");
		assertThat(await(alice, "BattleInviteSent").get("to_name").asString()).isEqualTo("Bob");
	}

	@Test
	void decliningNotifiesTheInviter() throws Exception {
		send(aliceSession, "BattleInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");
		JsonNode invite = await(bob, "BattleInviteReceived");

		send(bobSession, "BattleInviteResponse", "{\"invite_uuid\":\"" + invite.get("invite_uuid").asString() + "\",\"accept\":false}");

		assertThat(await(alice, "BattleInviteDeclined").get("by_name").asString()).isEqualTo("Bob");
	}

	@Test
	void invitingADisconnectedPlayerIsRejected() throws Exception {
		send(aliceSession, "BattleInvite", "{\"target_uuid\":\"" + UUID.randomUUID() + "\"}");

		assertThat(await(alice, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_PARTNER_UNAVAILABLE");
	}

	@Test
	void aPlayerWithoutTeamCannotGetReady() throws Exception {
		// The lobby still opens: they may switch to their Cobblemon team there.
		teamMember(aliceUuid, "pikachu", 1);
		String lobby = openLobby(aliceSession, alice, bobUuid, bobSession, bob).get("lobby_uuid").asString();

		setReady(bobSession, lobby, true);

		assertThat(await(bob, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_EMPTY_TEAM");
		assertNothing(alice, "BattleSessionStarted");
	}

	@Test
	void theFirstBattleIsHostedByTheInviterWhoAloneGetsBothTeams() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		Pokemon bobMon = teamMember(bobUuid, "charmander", 1);

		send(aliceSession, "BattleInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");
		JsonNode invite = await(bob, "BattleInviteReceived");
		send(bobSession, "BattleInviteResponse", "{\"invite_uuid\":\"" + invite.get("invite_uuid").asString() + "\",\"accept\":true}");
		readyBoth(await(alice, "BattleLobbyUpdated").get("lobby_uuid").asString());

		JsonNode aliceView = await(alice, "BattleSessionStarted");
		assertThat(aliceView.get("role").asString()).isEqualTo("HOST");
		assertThat(aliceView.get("opponent_name").asString()).isEqualTo("Bob");
		assertThat(aliceView.get("own_team").get(0).get("uuid").asString()).isEqualTo(aliceMon.getUuid().toString());
		assertThat(aliceView.get("opponent_team").get(0).get("uuid").asString()).isEqualTo(bobMon.getUuid().toString());

		JsonNode bobView = await(bob, "BattleSessionStarted");
		assertThat(bobView.get("role").asString()).isEqualTo("GUEST");
		assertThat(bobView.get("own_team").get(0).get("uuid").asString()).isEqualTo(bobMon.getUuid().toString());
		assertThat(bobView.has("opponent_team")).as("the guest never receives the opponent's full sets").isFalse();

		BattleSession stored = battleRepository.findById(UUID.fromString(aliceView.get("battle_uuid").asString())).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(BattleStatus.ACTIVE);
		assertThat(stored.getHostUuid()).isEqualTo(aliceUuid);
		assertThat(stored.getTeamA()).containsExactly(aliceMon.getUuid());
		assertThat(stored.getTeamB()).containsExactly(bobMon.getUuid());
	}

	@Test
	void theHostAlternatesBetweenSuccessiveBattlesOfTheSamePair() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		JsonNode first = startBattle(aliceSession, alice, bobUuid, bobSession, bob);
		String firstUuid = first.get("battle_uuid").asString();
		send(aliceSession, "BattleResult", "{\"battle_uuid\":\"" + firstUuid + "\",\"winner_uuid\":\"" + aliceUuid + "\"}");
		await(bob, "BattleEnded");

		// Alice invites again — but she hosted last time, so Bob hosts now.
		JsonNode second = startBattle(aliceSession, alice, bobUuid, bobSession, bob);

		assertThat(second.get("role").asString()).isEqualTo("GUEST");
		assertThat(battleRepository.findById(UUID.fromString(second.get("battle_uuid").asString())).orElseThrow().getHostUuid())
				.isEqualTo(bobUuid);
	}

	@Test
	void hostPacketsReachTheGuestAndGuestChoicesReachTheHost() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(aliceSession, "BattlePacket", "{\"battle_uuid\":\"" + battle + "\",\"id\":\"cobblemon:battle_message\",\"payload\":\"AAEC\"}");
		JsonNode relayed = await(bob, "BattlePacket");
		assertThat(relayed.get("id").asString()).isEqualTo("cobblemon:battle_message");
		assertThat(relayed.get("payload").asString()).isEqualTo("AAEC");

		send(bobSession, "BattleChoice", "{\"battle_uuid\":\"" + battle + "\",\"payload\":\"BAUG\"}");
		assertThat(await(alice, "BattleChoice").get("payload").asString()).isEqualTo("BAUG");
	}

	@Test
	void onlyTheHostMayRelayBattlePacketsAndOnlyTheGuestMaySendChoices() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(bobSession, "BattlePacket", "{\"battle_uuid\":\"" + battle + "\",\"id\":\"x\",\"payload\":\"AA\"}");
		assertThat(await(bob, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_NOT_HOST");
		send(aliceSession, "BattleChoice", "{\"battle_uuid\":\"" + battle + "\",\"payload\":\"AA\"}");
		assertThat(await(alice, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_NOT_GUEST");
		assertNothing(alice, "BattlePacket");
	}

	@Test
	void eitherPlayerCanTurnTheTimerOnOnceForBoth() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(bobSession, "BattleTimerEnable", "{\"battle_uuid\":\"" + battle + "\"}");

		JsonNode hostSide = await(alice, "BattleTimerEnabled");
		assertThat(hostSide.get("by_name").asString()).isEqualTo("Bob");
		assertThat(hostSide.get("seconds").asInt()).isEqualTo(90);
		await(bob, "BattleTimerEnabled");

		send(aliceSession, "BattleTimerEnable", "{\"battle_uuid\":\"" + battle + "\"}");
		assertNothing(bob, "BattleTimerEnabled");
	}

	@Test
	void theHostReportsTheResultWhichIsStoredAndBroadcast() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(aliceSession, "BattleResult", "{\"battle_uuid\":\"" + battle + "\",\"winner_uuid\":\"" + bobUuid + "\"}");

		JsonNode ended = await(bob, "BattleEnded");
		assertThat(ended.get("winner_uuid").asString()).isEqualTo(bobUuid.toString());
		assertThat(ended.get("reason").asString()).isEqualTo("FINISHED");
		await(alice, "BattleEnded");
		BattleSession stored = battleRepository.findById(UUID.fromString(battle)).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(BattleStatus.FINISHED);
		assertThat(stored.getResult()).containsEntry("winner_uuid", bobUuid.toString());
	}

	@Test
	void aResultFromTheGuestOrNamingAStrangerIsRejected() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(bobSession, "BattleResult", "{\"battle_uuid\":\"" + battle + "\",\"winner_uuid\":\"" + bobUuid + "\"}");
		assertThat(await(bob, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_NOT_HOST");

		send(aliceSession, "BattleResult", "{\"battle_uuid\":\"" + battle + "\",\"winner_uuid\":\"" + UUID.randomUUID() + "\"}");
		assertThat(await(alice, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_INVALID_RESULT");
		assertThat(battleRepository.findById(UUID.fromString(battle)).orElseThrow().getStatus()).isEqualTo(BattleStatus.ACTIVE);
	}

	@Test
	void aDrawResultIsAccepted() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(aliceSession, "BattleResult", "{\"battle_uuid\":\"" + battle + "\",\"winner_uuid\":null}");

		JsonNode ended = await(bob, "BattleEnded");
		assertThat(ended.get("winner_uuid").isNull()).isTrue();
		assertThat(battleRepository.findById(UUID.fromString(battle)).orElseThrow().getStatus()).isEqualTo(BattleStatus.FINISHED);
	}

	@Test
	void leavingIsAForfeitWonByTheOtherPlayer() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		send(bobSession, "BattleLeave", "{\"battle_uuid\":\"" + battle + "\"}");

		JsonNode ended = await(alice, "BattleEnded");
		assertThat(ended.get("winner_uuid").asString()).isEqualTo(aliceUuid.toString());
		assertThat(ended.get("reason").asString()).isEqualTo("FORFEIT");
	}

	@Test
	void aStoppingBackendDeclaresEveryLiveBattleADraw() throws Exception {
		// CAD Partie 1 §44: backend lost -> no winner, draw, both players told before the connections close.
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		liveBattleService.endAllAsDraw("BACKEND_LOST");

		for (RecordingHandler player : List.of(alice, bob)) {
			JsonNode ended = await(player, "BattleEnded");
			assertThat(ended.get("reason").asString()).isEqualTo("BACKEND_LOST");
			assertThat(ended.get("winner_uuid").isNull()).isTrue();
		}
		BattleSession stored = battleRepository.findById(UUID.fromString(battle)).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(BattleStatus.FINISHED);
		assertThat(stored.getResult()).containsEntry("reason", "BACKEND_LOST").containsEntry("winner_uuid", null);
	}

	@Test
	void battlesLeftActiveByACrashedBackendAreClosedAsDrawsAtStartup() {
		BattleSession orphan = battleRepository.saveAndFlush(BattleSession.hosted(UUID.randomUUID(), aliceUuid, bobUuid,
				List.of(UUID.randomUUID()), List.of(UUID.randomUUID())));

		liveBattleService.closeOrphanedBattles();

		BattleSession stored = battleRepository.findById(orphan.getUuid()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(BattleStatus.FINISHED);
		assertThat(stored.getResult()).containsEntry("reason", "BACKEND_LOST").containsEntry("winner_uuid", null);
		assertThat(stored.getFinishedAt()).isNotNull();
	}

	@Test
	void disconnectingEndsTheBattleAsADraw() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();

		bobSession.close(CloseStatus.NORMAL);

		JsonNode ended = await(alice, "BattleEnded");
		assertThat(ended.get("reason").asString()).isEqualTo("PARTNER_DISCONNECTED");
		assertThat(ended.get("winner_uuid").isNull()).isTrue();
		assertThat(battleRepository.findById(UUID.fromString(battle)).orElseThrow().getStatus()).isEqualTo(BattleStatus.ABORTED);
	}

	@Test
	void aLargeBattlePacketIsRelayedWhole() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String battle = startBattle(aliceSession, alice, bobUuid, bobSession, bob).get("battle_uuid").asString();
		String big = "A".repeat(100_000);

		send(aliceSession, "BattlePacket", "{\"battle_uuid\":\"" + battle + "\",\"id\":\"cobblemon:battle_set_team\",\"payload\":\"" + big + "\"}");

		assertThat(await(bob, "BattlePacket").get("payload").asString()).hasSize(100_000);
	}

	// ---- Lobby (team preview, hidden lead, ready, lobby timer) ----

	private Pokemon detailedTeamMember(UUID ownerUuid, String species, int teamSlot) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), ownerUuid, species, null,
				(short) 50, "timid", "static", true, null, null, (short) teamSlot, "1.8.1",
				Map.of("ivs", Map.of("hp", 31), "evs", Map.of("spa", 252), "moves", List.of("thunderbolt"),
						"held_item", "light_ball", "gender", "F")));
	}

	@Test
	void acceptingOpensALobbyWhereTheOpponentsTeamIsOnlyAPreview() throws Exception {
		detailedTeamMember(aliceUuid, "pikachu", 1);
		detailedTeamMember(bobUuid, "charmander", 1);

		JsonNode aliceView = openLobby(aliceSession, alice, bobUuid, bobSession, bob);

		assertThat(aliceView.get("opponent_name").asString()).isEqualTo("Bob");
		assertThat(aliceView.get("own_team").get(0).get("data").get("moves").get(0).asString())
				.as("a player sees their own sets in full").isEqualTo("thunderbolt");
		assertThat(aliceView.get("own_lead").asInt()).isZero();
		assertThat(aliceView.get("own_ready").asBoolean()).isFalse();
		assertThat(aliceView.get("opponent_ready").asBoolean()).isFalse();
		assertThat(aliceView.get("opponent_team_source").asString()).isEqualTo("GHOST");
		JsonNode preview = aliceView.get("opponent_team").get(0);
		assertThat(preview.get("species").asString()).isEqualTo("charmander");
		assertThat(preview.get("is_shiny").asBoolean()).isTrue();
		java.util.Set<String> fields = new java.util.HashSet<>(preview.propertyNames());
		assertThat(fields).as("model and name only: no level, set, IV/EV or item").containsOnly("species", "form", "is_shiny", "gender");
		assertNothing(alice, "BattleSessionStarted");
	}

	@Test
	void theLeadStaysHiddenFromTheOpponentAndGoesFirstWhenTheBattleStarts() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(aliceUuid, "eevee", 2);
		teamMember(bobUuid, "charmander", 1);
		String lobby = openLobby(aliceSession, alice, bobUuid, bobSession, bob).get("lobby_uuid").asString();

		setLead(aliceSession, lobby, 1);
		assertThat(await(alice, "BattleLobbyUpdated").get("own_lead").asInt()).isEqualTo(1);
		assertNothing(bob, "BattleLobbyUpdated");

		setReady(aliceSession, lobby, true);
		JsonNode bobView = await(bob, "BattleLobbyUpdated");
		assertThat(bobView.get("opponent_ready").asBoolean()).isTrue();
		assertThat(bobView.toString()).as("nothing about the opponent's lead").doesNotContain("opponent_lead");
		assertThat(bobView.get("opponent_team").get(0).get("species").asString()).as("preview in team order").isEqualTo("pikachu");

		setReady(bobSession, lobby, true);
		JsonNode aliceStart = await(alice, "BattleSessionStarted");
		assertThat(aliceStart.get("own_team").get(0).get("species").asString()).isEqualTo("eevee");
		assertThat(aliceStart.get("own_team").get(1).get("species").asString()).isEqualTo("pikachu");
		assertThat(await(bob, "BattleSessionStarted").get("own_team").get(0).get("species").asString()).isEqualTo("charmander");
	}

	@Test
	void aReadyPlayerIsLockedAndATeamSwitchUnreadiesTheOpponent() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(aliceUuid, "eevee", 2);
		teamMember(bobUuid, "charmander", 1);
		String lobby = openLobby(aliceSession, alice, bobUuid, bobSession, bob).get("lobby_uuid").asString();
		setReady(aliceSession, lobby, true);
		await(bob, "BattleLobbyUpdated");

		setLead(aliceSession, lobby, 1);
		assertThat(await(alice, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_LOBBY_LOCKED");

		send(bobSession, "BattleLobbySetTeam", "{\"lobby_uuid\":\"" + lobby + "\",\"team\":\"COBBLEMON\",\"party\":["
				+ partyMember(UUID.randomUUID(), "garchomp", "{}") + "]}");
		JsonNode aliceView = await(alice, "BattleLobbyUpdated");
		while (!aliceView.get("opponent_team_source").asString().equals("COBBLEMON")) {
			aliceView = await(alice, "BattleLobbyUpdated");
		}
		assertThat(aliceView.get("own_ready").asBoolean()).as("the opponent's team changed: look again").isFalse();
		assertThat(aliceView.get("opponent_team").get(0).get("species").asString()).isEqualTo("garchomp");
		JsonNode bobView = await(bob, "BattleLobbyUpdated");
		while (!bobView.get("own_team_source").asString().equals("COBBLEMON")) {
			bobView = await(bob, "BattleLobbyUpdated");
		}
		assertThat(bobView.get("own_team").get(0).get("species").asString()).isEqualTo("garchomp");

		send(bobSession, "BattleLobbySetTeam", "{\"lobby_uuid\":\"" + lobby + "\",\"team\":\"GHOST\"}");
		assertThat(await(alice, "BattleLobbyUpdated").get("opponent_team").get(0).get("species").asString()).isEqualTo("charmander");
	}

	@Test
	void playersInALobbyAreBusyAndKeepTheirGhostsIn() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		openLobby(aliceSession, alice, bobUuid, bobSession, bob);

		send(aliceSession, "SendOutGhost", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");
		assertThat(await(alice, "Error").get("error_code").asString()).isEqualTo("ERROR_GHOST_IN_BATTLE");
		send(bobSession, "BattleInvite", "{\"target_uuid\":\"" + aliceUuid + "\"}");
		assertThat(await(bob, "BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_ALREADY_IN_BATTLE");
	}

	@Test
	void leavingTheLobbyCancelsItForBoth() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		String lobby = openLobby(aliceSession, alice, bobUuid, bobSession, bob).get("lobby_uuid").asString();

		send(bobSession, "BattleLobbyLeave", "{\"lobby_uuid\":\"" + lobby + "\"}");

		JsonNode cancelled = await(alice, "BattleLobbyCancelled");
		assertThat(cancelled.get("reason").asString()).isEqualTo("LEFT");
		assertThat(cancelled.get("by_name").asString()).isEqualTo("Bob");
		assertThat(await(bob, "BattleLobbyCancelled").get("lobby_uuid").asString()).isEqualTo(lobby);
		// Free again: a new invitation goes through.
		send(bobSession, "BattleInvite", "{\"target_uuid\":\"" + aliceUuid + "\"}");
		await(alice, "BattleInviteReceived");
	}

	@Test
	void disconnectingOrAStoppingBackendCancelsTheLobby() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		openLobby(aliceSession, alice, bobUuid, bobSession, bob);
		liveBattleService.endAllAsDraw("BACKEND_LOST");
		assertThat(await(alice, "BattleLobbyCancelled").get("reason").asString()).isEqualTo("BACKEND_LOST");
		assertThat(await(bob, "BattleLobbyCancelled").get("reason").asString()).isEqualTo("BACKEND_LOST");

		openLobby(aliceSession, alice, bobUuid, bobSession, bob);
		bobSession.close(CloseStatus.NORMAL);
		assertThat(await(alice, "BattleLobbyCancelled").get("reason").asString()).isEqualTo("PARTNER_DISCONNECTED");
	}

	@Test
	void theLobbyTimerPicksTheFirstPokemonForWhoeverIsNotReadyAndCarriesOverToTheBattle() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		teamMember(aliceUuid, "eevee", 2);
		teamMember(bobUuid, "charmander", 1);
		teamMember(bobUuid, "squirtle", 2);
		String lobby = openLobby(aliceSession, alice, bobUuid, bobSession, bob).get("lobby_uuid").asString();
		setLead(aliceSession, lobby, 1);
		setReady(aliceSession, lobby, true);
		setLead(bobSession, lobby, 1);

		send(bobSession, "BattleLobbyTimerEnable", "{\"lobby_uuid\":\"" + lobby + "\"}");
		JsonNode timed = await(alice, "BattleLobbyUpdated");
		while (timed.get("timer_seconds_left").isNull()) {
			timed = await(alice, "BattleLobbyUpdated");
		}
		assertThat(timed.get("timer_seconds_left").asInt()).isBetween(148, 150);
		assertThat(timed.get("timer_by_name").asString()).isEqualTo("Bob");

		liveBattleService.expireLobbies(java.time.Instant.now().plusSeconds(151));

		assertThat(await(alice, "BattleSessionStarted").get("own_team").get(0).get("species").asString())
				.as("Alice was ready: her lead is kept").isEqualTo("eevee");
		assertThat(await(bob, "BattleSessionStarted").get("own_team").get(0).get("species").asString())
				.as("Bob was not ready: his first Pokémon leads").isEqualTo("charmander");
		JsonNode battleTimer = await(alice, "BattleTimerEnabled");
		assertThat(battleTimer.get("seconds").asInt()).isEqualTo(90);
		assertThat(battleTimer.get("by_name").asString()).isEqualTo("Bob");
		await(bob, "BattleTimerEnabled");
	}
}
