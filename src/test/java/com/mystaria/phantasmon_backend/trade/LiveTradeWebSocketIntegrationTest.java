package com.mystaria.phantasmon_backend.trade;

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
 * Live (real-time, both players on screen) trade sessions over the presence
 * WebSocket — the backend half of the graphical trade screen. Same
 * real-Tomcat + real-WebSocket-client approach as
 * {@code PhantasmonWebSocketIntegrationTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "phantasmon.logging.enabled=false")
class LiveTradeWebSocketIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private PokemonRepository pokemonRepository;

	@Autowired
	private TradeRepository tradeRepository;

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
		return new StandardWebSocketClient()
				.execute(handler, "ws://localhost:" + port + "/ws?token=" + token)
				.get(5, TimeUnit.SECONDS);
	}

	private Pokemon teamMember(UUID ownerUuid, String species, int teamSlot) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), ownerUuid, species, null,
				(short) 50, "timid", "static", false, null, null, (short) teamSlot, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of(), "gender", "MALE")));
	}

	private Pokemon pcOnly(UUID ownerUuid) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), ownerUuid, "eevee", null,
				(short) 10, "timid", "adaptability", false, (short) 1, (short) 1, null, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	private static void send(WebSocketSession session, String type, String dataJson) throws Exception {
		session.sendMessage(new TextMessage("{\"type\":\"" + type + "\",\"data\":" + dataJson + "}"));
	}

	/** Skips any unrelated message (heartbeats, catch-ups...) until one of the wanted type arrives. */
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

	/** Invites Bob, has him accept, and returns once both sides received {@code TradeSessionStarted}. */
	private void startSession() throws Exception {
		send(aliceSession, "TradeInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");
		JsonNode invite = await(bob, "TradeInviteReceived");
		send(bobSession, "TradeInviteResponse",
				"{\"invite_uuid\":\"" + invite.get("invite_uuid").asString() + "\",\"accept\":true}");
		await(alice, "TradeSessionStarted");
		await(bob, "TradeSessionStarted");
	}

	@Test
	void inviteReachesTheTargetWithTheInvitersName() throws Exception {
		send(aliceSession, "TradeInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");

		JsonNode received = await(bob, "TradeInviteReceived");
		assertThat(received.get("from_uuid").asString()).isEqualTo(aliceUuid.toString());
		assertThat(received.get("from_name").asString()).isEqualTo("Alice");
		assertThat(received.get("invite_uuid").asString()).isNotBlank();

		JsonNode sent = await(alice, "TradeInviteSent");
		assertThat(sent.get("to_name").asString()).isEqualTo("Bob");
	}

	@Test
	void invitingADisconnectedPlayerIsRejected() throws Exception {
		send(aliceSession, "TradeInvite", "{\"target_uuid\":\"" + UUID.randomUUID() + "\"}");

		JsonNode error = await(alice, "TradeSessionError");
		assertThat(error.get("error_code").asString()).isEqualTo("ERROR_TRADE_PARTNER_UNAVAILABLE");
	}

	@Test
	void invitingYourselfIsRejected() throws Exception {
		send(aliceSession, "TradeInvite", "{\"target_uuid\":\"" + aliceUuid + "\"}");

		JsonNode error = await(alice, "TradeSessionError");
		assertThat(error.get("error_code").asString()).isEqualTo("ERROR_TRADE_SELF");
	}

	@Test
	void decliningNotifiesTheInviter() throws Exception {
		send(aliceSession, "TradeInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");
		JsonNode invite = await(bob, "TradeInviteReceived");

		send(bobSession, "TradeInviteResponse",
				"{\"invite_uuid\":\"" + invite.get("invite_uuid").asString() + "\",\"accept\":false}");

		JsonNode declined = await(alice, "TradeInviteDeclined");
		assertThat(declined.get("by_name").asString()).isEqualTo("Bob");
	}

	@Test
	void acceptingStartsASessionWithBothTeams() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		Pokemon bobMon = teamMember(bobUuid, "charmander", 2);
		pcOnly(bobUuid);

		send(aliceSession, "TradeInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");
		JsonNode invite = await(bob, "TradeInviteReceived");
		send(bobSession, "TradeInviteResponse",
				"{\"invite_uuid\":\"" + invite.get("invite_uuid").asString() + "\",\"accept\":true}");

		JsonNode aliceView = await(alice, "TradeSessionStarted");
		assertThat(aliceView.get("partner_uuid").asString()).isEqualTo(bobUuid.toString());
		assertThat(aliceView.get("partner_name").asString()).isEqualTo("Bob");
		assertThat(aliceView.get("own_team")).hasSize(1);
		assertThat(aliceView.get("own_team").get(0).get("uuid").asString()).isEqualTo(aliceMon.getUuid().toString());
		assertThat(aliceView.get("partner_team")).as("only the partner's team, never their PC").hasSize(1);
		assertThat(aliceView.get("partner_team").get(0).get("species").asString()).isEqualTo("charmander");
		assertThat(aliceView.get("partner_team").get(0).get("data").get("gender").asString()).isEqualTo("MALE");

		JsonNode bobView = await(bob, "TradeSessionStarted");
		assertThat(bobView.get("partner_name").asString()).isEqualTo("Alice");
		assertThat(bobView.get("own_team").get(0).get("uuid").asString()).isEqualTo(bobMon.getUuid().toString());
	}

	@Test
	void selectingAnOfferIsRelayedToBothSides() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		startSession();

		send(aliceSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");

		JsonNode aliceUpdate = await(alice, "TradeSessionUpdate");
		assertThat(aliceUpdate.get("own_offer").asString()).isEqualTo(aliceMon.getUuid().toString());
		JsonNode bobUpdate = await(bob, "TradeSessionUpdate");
		assertThat(bobUpdate.get("partner_offer").asString()).isEqualTo(aliceMon.getUuid().toString());
	}

	@Test
	void offeringAPcPokemonIsRejected() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		Pokemon pcMon = pcOnly(aliceUuid);
		startSession();

		send(aliceSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + pcMon.getUuid() + "\"}");

		assertThat(await(alice, "TradeSessionError").get("error_code").asString()).isEqualTo("ERROR_TRADE_OFFER_NOT_IN_TEAM");
	}

	@Test
	void offeringSomeoneElsesPokemonIsRejected() throws Exception {
		teamMember(aliceUuid, "pikachu", 1);
		Pokemon bobMon = teamMember(bobUuid, "charmander", 1);
		startSession();

		send(aliceSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + bobMon.getUuid() + "\"}");

		assertThat(await(alice, "TradeSessionError").get("error_code").asString()).isEqualTo("ERROR_OWNERSHIP_MISMATCH");
	}

	@Test
	void readyRequiresBothOffers() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		teamMember(bobUuid, "charmander", 1);
		startSession();
		send(aliceSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");
		await(alice, "TradeSessionUpdate");

		send(aliceSession, "TradeSetReady", "{\"ready\":true}");

		assertThat(await(alice, "TradeSessionError").get("error_code").asString()).isEqualTo("ERROR_TRADE_OFFERS_INCOMPLETE");
	}

	@Test
	void changingAnOfferResetsBothReadyFlags() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 1);
		Pokemon aliceOther = teamMember(aliceUuid, "bulbasaur", 2);
		Pokemon bobMon = teamMember(bobUuid, "charmander", 1);
		startSession();
		send(aliceSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");
		await(bob, "TradeSessionUpdate");
		send(bobSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + bobMon.getUuid() + "\"}");
		await(bob, "TradeSessionUpdate");
		send(bobSession, "TradeSetReady", "{\"ready\":true}");
		JsonNode readyUpdate = await(bob, "TradeSessionUpdate");
		assertThat(readyUpdate.get("own_ready").asBoolean()).isTrue();

		send(aliceSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + aliceOther.getUuid() + "\"}");

		JsonNode afterChange = await(bob, "TradeSessionUpdate");
		assertThat(afterChange.get("partner_offer").asString()).isEqualTo(aliceOther.getUuid().toString());
		assertThat(afterChange.get("own_ready").asBoolean()).as("any offer change cancels everyone's ready state").isFalse();
		assertThat(afterChange.get("partner_ready").asBoolean()).isFalse();
	}

	@Test
	void bothReadyCompletesTheTradeAtomicallyKeepingTeamSlots() throws Exception {
		Pokemon aliceMon = teamMember(aliceUuid, "pikachu", 3);
		Pokemon bobMon = teamMember(bobUuid, "charmander", 5);
		startSession();
		send(aliceSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + aliceMon.getUuid() + "\"}");
		send(bobSession, "TradeSelectOffer", "{\"pokemon_uuid\":\"" + bobMon.getUuid() + "\"}");
		await(alice, "TradeSessionUpdate");
		await(alice, "TradeSessionUpdate");
		send(aliceSession, "TradeSetReady", "{\"ready\":true}");
		await(bob, "TradeSessionUpdate");
		await(bob, "TradeSessionUpdate");
		await(bob, "TradeSessionUpdate");

		send(bobSession, "TradeSetReady", "{\"ready\":true}");

		JsonNode aliceDone = await(alice, "TradeSessionCompleted");
		assertThat(aliceDone.get("given_pokemon").asString()).isEqualTo(aliceMon.getUuid().toString());
		assertThat(aliceDone.get("received_pokemon").asString()).isEqualTo(bobMon.getUuid().toString());
		JsonNode bobDone = await(bob, "TradeSessionCompleted");
		assertThat(bobDone.get("received_pokemon").asString()).isEqualTo(aliceMon.getUuid().toString());

		Pokemon aliceMonAfter = pokemonRepository.findById(aliceMon.getUuid()).orElseThrow();
		Pokemon bobMonAfter = pokemonRepository.findById(bobMon.getUuid()).orElseThrow();
		assertThat(aliceMonAfter.getOwnerUuid()).isEqualTo(bobUuid);
		assertThat(aliceMonAfter.getTeamSlot()).as("takes the team slot of the Pokémon Bob gave away").isEqualTo((short) 5);
		assertThat(bobMonAfter.getOwnerUuid()).isEqualTo(aliceUuid);
		assertThat(bobMonAfter.getTeamSlot()).isEqualTo((short) 3);
		assertThat(aliceMonAfter.getBoxId()).isNull();

		Trade recorded = tradeRepository.findById(UUID.fromString(aliceDone.get("trade_uuid").asString())).orElseThrow();
		assertThat(recorded.getStatus()).isEqualTo(TradeStatus.COMPLETED);
		assertThat(recorded.getInitiatorUuid()).isEqualTo(aliceUuid);
		assertThat(recorded.getOfferedPokemon()).isEqualTo(aliceMon.getUuid());
		assertThat(recorded.getRequestedPokemon()).isEqualTo(bobMon.getUuid());
	}

	@Test
	void leavingCancelsTheSessionForThePartner() throws Exception {
		startSession();

		send(aliceSession, "TradeLeave", "{}");

		assertThat(await(bob, "TradeSessionCancelled").get("reason").asString()).isEqualTo("PARTNER_LEFT");
	}

	@Test
	void disconnectingCancelsTheSessionForThePartner() throws Exception {
		startSession();

		aliceSession.close(CloseStatus.NORMAL);

		assertThat(await(bob, "TradeSessionCancelled").get("reason").asString()).isEqualTo("PARTNER_DISCONNECTED");
	}

	@Test
	void aPlayerAlreadyTradingCannotBeInvited() throws Exception {
		startSession();
		UUID carolUuid = UUID.randomUUID();
		playerService.recordConnection(carolUuid, "Carol");
		RecordingHandler carol = new RecordingHandler();
		WebSocketSession carolSession = connect(carolUuid, "Carol", carol);
		try {
			send(carolSession, "TradeInvite", "{\"target_uuid\":\"" + bobUuid + "\"}");

			assertThat(await(carol, "TradeSessionError").get("error_code").asString()).isEqualTo("ERROR_TRADE_PARTNER_BUSY");
		} finally {
			carolSession.close();
		}
	}
}
