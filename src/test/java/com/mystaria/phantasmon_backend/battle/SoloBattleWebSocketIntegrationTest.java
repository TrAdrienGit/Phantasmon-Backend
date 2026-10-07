package com.mystaria.phantasmon_backend.battle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin solo battle (Adrien 2026-10-07): an admin fights a mirror of their own Ghost team, played by Cobblemon's AI on
 * their client — a real live battle for the backend, so spectators can watch it, but never stored.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"phantasmon.logging.enabled=false", "phantasmon.admin.file=build/test-solo-admins.txt" })
class SoloBattleWebSocketIntegrationTest {

	private static final Path ADMIN_FILE = Path.of("build/test-solo-admins.txt");

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
	private ObjectMapper objectMapper;

	private final List<WebSocketSession> sessions = new ArrayList<>();

	private final class Client extends TextWebSocketHandler {
		final UUID uuid = UUID.randomUUID();
		final String name;
		final BlockingQueue<String> received = new LinkedBlockingQueue<>();
		WebSocketSession session;

		Client(String prefix) {
			this.name = prefix + uuid.toString().substring(0, 6);
		}

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			received.add(message.getPayload());
		}

		void send(String type, String data) throws Exception {
			session.sendMessage(new TextMessage("{\"type\":\"" + type + "\",\"data\":" + data + "}"));
		}

		JsonNode await(String type) throws Exception {
			long deadline = System.currentTimeMillis() + 5000;
			while (System.currentTimeMillis() < deadline) {
				String raw = received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
				if (raw != null) {
					JsonNode node = objectMapper.readTree(raw);
					if (type.equals(node.get("type").asString())) {
						return node.get("data");
					}
				}
			}
			throw new AssertionError(name + " never received " + type);
		}

		int countWithin(String type, long millis) throws Exception {
			int count = 0;
			long deadline = System.currentTimeMillis() + millis;
			while (System.currentTimeMillis() < deadline) {
				String raw = received.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
				if (raw != null && type.equals(objectMapper.readTree(raw).get("type").asString())) {
					count++;
				}
			}
			return count;
		}
	}

	private Client connect(String prefix, boolean admin) throws Exception {
		Client client = new Client(prefix);
		playerService.recordConnection(client.uuid, client.name);
		if (admin) {
			Files.createDirectories(ADMIN_FILE.getParent());
			Files.writeString(ADMIN_FILE, client.name + "\n");
			Files.setLastModifiedTime(ADMIN_FILE, FileTime.from(Instant.now().plusSeconds((long) (Math.random() * 1000) + 1)));
		}
		client.session = new StandardWebSocketClient().execute(client,
				"ws://localhost:" + port + "/ws?token=" + jwtService.issueAccessToken(client.uuid, client.name)).get(5, TimeUnit.SECONDS);
		sessions.add(client.session);
		return client;
	}

	private void teamMember(Client owner, String species, int slot) {
		pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), owner.uuid, species, null, (short) 50, "timid",
				"static", false, null, null, (short) slot, "1.8.1", Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	@AfterEach
	void close() throws Exception {
		for (WebSocketSession session : sessions) {
			if (session.isOpen()) {
				session.close();
			}
		}
	}

	@Test
	void onlyAnAdminMayFightThemself() throws Exception {
		Client player = connect("Pl", false);
		teamMember(player, "pikachu", 1);
		player.send("BattleSoloStart", "{}");
		assertThat(player.await("BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_ADMIN_REQUIRED");
	}

	@Test
	void anAdminFightsAMirrorOfTheirTeamThatOthersCanWatchAndNothingIsStored() throws Exception {
		Client admin = connect("Op", true);
		teamMember(admin, "pikachu", 1);
		teamMember(admin, "eevee", 2);
		long storedBefore = battleRepository.count();

		admin.send("BattleSoloStart", "{}");
		JsonNode started = admin.await("BattleSessionStarted");
		String battle = started.get("battle_uuid").asString();
		assertThat(started.get("role").asString()).isEqualTo("HOST");
		assertThat(started.get("solo").asBoolean()).isTrue();
		String mirror = started.get("opponent_uuid").asString();
		assertThat(mirror).isNotEqualTo(admin.uuid.toString());
		assertThat(started.get("opponent_name").asString()).contains(admin.name);
		assertThat(started.get("own_team")).hasSize(2);
		assertThat(started.get("opponent_team")).hasSize(2);
		assertThat(started.get("opponent_team").get(0).get("species").asString()).isEqualTo("pikachu");

		Client carol = connect("Ca", false);
		carol.send("BattleSpectate", "{\"target_uuid\":\"" + admin.uuid + "\"}");
		assertThat(carol.await("BattleSpectateStarted").get("battle_uuid").asString()).isEqualTo(battle);

		// The mirror (the AI) wins: accepted, broadcast once, never stored.
		admin.send("BattleResult", "{\"battle_uuid\":\"" + battle + "\",\"winner_uuid\":\"" + mirror + "\"}");
		JsonNode ended = admin.await("BattleEnded");
		assertThat(ended.get("winner_uuid").asString()).isEqualTo(mirror);
		assertThat(admin.countWithin("BattleEnded", 600)).as("one BattleEnded, not one per side").isZero();
		assertThat(carol.await("BattleSpectateEnded").get("winner_uuid").asString()).isEqualTo(mirror);
		assertThat(battleRepository.count()).isEqualTo(storedBefore);
	}

	@Test
	void anAdminWithoutGhostTeamCannotStart() throws Exception {
		Client admin = connect("Op", true);
		admin.send("BattleSoloStart", "{}");
		assertThat(admin.await("BattleSessionError").get("error_code").asString()).isEqualTo("ERROR_BATTLE_EMPTY_TEAM");
	}
}
