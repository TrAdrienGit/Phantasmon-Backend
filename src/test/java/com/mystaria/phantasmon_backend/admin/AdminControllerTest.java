package com.mystaria.phantasmon_backend.admin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TODO-25 — admins listed in a file (one Minecraft name per line), and what they may do that players may not. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "phantasmon.logging.enabled=false", "phantasmon.admin.file=build/test-admins.txt" })
@AutoConfigureMockMvc
class AdminControllerTest {

	private static final Path ADMIN_FILE = Path.of("build/test-admins.txt");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private PokemonRepository pokemonRepository;

	private UUID adminUuid;
	private UUID playerUuid;
	private String adminName;
	private String playerName;
	private String adminToken;
	private String playerToken;

	@BeforeEach
	void setUp() throws Exception {
		adminUuid = UUID.randomUUID();
		playerUuid = UUID.randomUUID();
		adminName = "Op" + adminUuid.toString().substring(0, 8);
		playerName = "Pl" + playerUuid.toString().substring(0, 8);
		playerService.recordConnection(adminUuid, adminName);
		playerService.recordConnection(playerUuid, playerName);
		adminToken = "Bearer " + jwtService.issueAccessToken(adminUuid, adminName);
		playerToken = "Bearer " + jwtService.issueAccessToken(playerUuid, playerName);
		writeAdmins("# Phantasmon admins\n" + adminName.toUpperCase() + "\n");
	}

	private static void writeAdmins(String content) throws Exception {
		Files.createDirectories(ADMIN_FILE.getParent());
		Files.writeString(ADMIN_FILE, content);
		// A distinct modification time, so the change is seen even within the same second.
		Files.setLastModifiedTime(ADMIN_FILE, FileTime.from(Instant.now().plusSeconds((long) (Math.random() * 1000) + 1)));
	}

	private Pokemon pokemonOf(UUID owner, int boxSlot) {
		return pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), owner, "pikachu", null,
				(short) 50, "timid", "static", false, (short) 1, (short) boxSlot, null, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	@Test
	void theAdminFileDecidesWhoIsAdminCaseInsensitivelyAndIsReReadWhenItChanges() throws Exception {
		mockMvc.perform(get("/admin/me").header("Authorization", adminToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));
		mockMvc.perform(get("/admin/me").header("Authorization", playerToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(false));

		writeAdmins(playerName + "\n");

		mockMvc.perform(get("/admin/me").header("Authorization", adminToken)).andExpect(jsonPath("$.admin").value(false));
		mockMvc.perform(get("/admin/me").header("Authorization", playerToken)).andExpect(jsonPath("$.admin").value(true));
	}

	@Test
	void onlyAnAdminLooksPlayersUpByName() throws Exception {
		mockMvc.perform(get("/admin/players/" + playerName.toLowerCase()).header("Authorization", adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.uuid").value(playerUuid.toString()))
				.andExpect(jsonPath("$.name").value(playerName));
		mockMvc.perform(get("/admin/players/" + adminName).header("Authorization", playerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_ADMIN_REQUIRED"));
		mockMvc.perform(get("/admin/players/Nobody123").header("Authorization", adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_PLAYER_NOT_FOUND"));
	}

	@Test
	void anAdminWorksOnAnotherPlayersPcAsIfItWereTheirOwn() throws Exception {
		Pokemon pikachu = pokemonOf(playerUuid, 1);
		mockMvc.perform(get("/players/" + playerUuid + "/pokemon").header("Authorization", adminToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].uuid").value(pikachu.getUuid().toString()));

		mockMvc.perform(patch("/pokemon/" + pikachu.getUuid()).header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\":1}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.team_slot").value(1));
		assertThat(pokemonRepository.findById(pikachu.getUuid()).orElseThrow().getOwnerUuid())
				.as("still the player's Pokémon").isEqualTo(playerUuid);

		mockMvc.perform(post("/pokemon?owner=" + playerUuid).header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"request_uuid":"%s","species":"eevee","level":20,"nature":"timid","ability":"adaptability",
								 "cobblemon_data_version":"1.8.1","data":{"ivs":{},"evs":{}}}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.owner_uuid").value(playerUuid.toString()));

		mockMvc.perform(delete("/pokemon/" + pikachu.getUuid()).header("Authorization", adminToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void aPlayerStillCannotTouchSomeoneElsesPc() throws Exception {
		Pokemon adminsPikachu = pokemonOf(adminUuid, 1);
		mockMvc.perform(get("/players/" + adminUuid + "/pokemon").header("Authorization", playerToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/pokemon/" + adminsPikachu.getUuid()).header("Authorization", playerToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/pokemon?owner=" + adminUuid).header("Authorization", playerToken)
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"request_uuid":"%s","species":"eevee","level":20,"nature":"timid","ability":"adaptability",
								 "cobblemon_data_version":"1.8.1","data":{"ivs":{},"evs":{}}}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.error_code").value("ERROR_ADMIN_REQUIRED"));
	}

	@Test
	void stoppingABattleNeedsAnAdminAndSomethingToStop() throws Exception {
		mockMvc.perform(post("/admin/battles/stop").header("Authorization", playerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"player\":\"" + adminName + "\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/battles/stop").header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"player\":\"" + playerName + "\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_ADMIN_NOTHING_TO_STOP"));
		mockMvc.perform(post("/admin/reboot").header("Authorization", playerToken))
				.andExpect(status().isForbidden());
	}
}
