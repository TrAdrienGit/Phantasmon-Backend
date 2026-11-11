package com.mystaria.phantasmon_backend.reward;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rewards (TODO-37): admins give looks; a player's Pokémon may only wear what they won; an admin's seal holds. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "phantasmon.logging.enabled=false", "phantasmon.admin.file=build/test-admins.txt" })
@AutoConfigureMockMvc
class RewardControllerTest {

	private static final Path ADMIN_FILE = Path.of("build/test-admins.txt");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	private final ObjectMapper json = new ObjectMapper();

	private String adminToken;
	private String playerToken;
	private String playerName;

	@BeforeEach
	void setUp() throws Exception {
		UUID admin = UUID.randomUUID();
		String adminName = "Op" + admin.toString().substring(0, 8);
		playerService.recordConnection(admin, adminName);
		adminToken = "Bearer " + jwtService.issueAccessToken(admin, adminName);
		UUID player = UUID.randomUUID();
		playerName = "Pl" + player.toString().substring(0, 8);
		playerService.recordConnection(player, playerName);
		playerToken = "Bearer " + jwtService.issueAccessToken(player, playerName);
		Files.createDirectories(ADMIN_FILE.getParent());
		Files.writeString(ADMIN_FILE, adminName + "\n");
		Files.setLastModifiedTime(ADMIN_FILE, FileTime.from(Instant.now().plusSeconds((long) (Math.random() * 1000) + 1)));
	}

	private String createGardevoir() throws Exception {
		String body = mockMvc.perform(post("/pokemon").header("Authorization", playerToken).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"request_uuid":"%s","species":"gardevoir","level":50,"nature":"timid","ability":"trace",
								 "cobblemon_data_version":"1.8.1","data":{"moves":["psychic"]}}""".formatted(UUID.randomUUID())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.readTree(body).get("uuid").asString();
	}

	private org.springframework.test.web.servlet.ResultActions setLook(String token, String pokemon, String look) throws Exception {
		return mockMvc.perform(patch("/pokemon/" + pokemon).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"data\":{\"moves\":[\"psychic\"],\"look\":" + look + "}}"));
	}

	@Test
	void playersWearOnlyWhatTheyWonAndAdminsSeal() throws Exception {
		String gardevoir = createGardevoir();

		// Nothing won yet: refused.
		setLook(playerToken, gardevoir, "{\"aura\":\"super_saiyan\"}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_LOOK_NOT_OWNED"));
		mockMvc.perform(post("/admin/players/" + playerName + "/rewards").header("Authorization", playerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"AURA\",\"value\":\"super_saiyan\"}"))
				.andExpect(status().isForbidden());

		// An aura for Gardevoir only, and resizing for every Pokémon.
		mockMvc.perform(post("/admin/players/" + playerName + "/rewards").header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"kind\":\"AURA\",\"value\":\"super_saiyan\",\"species\":\"Gardevoir\",\"note\":\"Tournoi\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.species").value("gardevoir"));
		mockMvc.perform(post("/admin/players/" + playerName + "/rewards").header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"SCALE\"}"))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/admin/players/" + playerName + "/rewards").header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"SCALE\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error_code").value("ERROR_REWARD_DUPLICATE"));
		mockMvc.perform(get("/rewards/mine").header("Authorization", playerToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

		setLook(playerToken, gardevoir, "{\"aura\":\"super_saiyan\",\"scale\":150}").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.look.aura").value("super_saiyan"));
		setLook(playerToken, gardevoir, "{\"aura\":\"super_saiyan\",\"filter\":\"negative\"}").andExpect(status().isForbidden());
		setLook(playerToken, gardevoir, "{\"scale\":300}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ERROR_LOOK_INVALID"));
		setLook(playerToken, gardevoir, "{\"sealed\":true}").andExpect(status().isForbidden());

		// The admin seals a look: the player can no longer change it.
		setLook(adminToken, gardevoir, "{\"filter\":\"obscure\",\"sealed\":true}").andExpect(status().isOk());
		setLook(playerToken, gardevoir, "null").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_LOOK_SEALED"));

		JsonNode rewards = json.readTree(mockMvc.perform(get("/admin/players/" + playerName + "/rewards")
				.header("Authorization", adminToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		String first = rewards.get("rewards").get(0).get("uuid").asString();
		mockMvc.perform(delete("/admin/rewards/" + first).header("Authorization", adminToken)).andExpect(status().isNoContent());
		mockMvc.perform(get("/rewards/mine").header("Authorization", playerToken)).andExpect(jsonPath("$.length()").value(1));
	}
}
