package com.mystaria.phantasmon_backend.pokemon;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@AutoConfigureMockMvc
class PokemonControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	private UUID ownerUuid;
	private String bearerToken;

	@BeforeEach
	void setUpAuthenticatedPlayer() {
		ownerUuid = UUID.randomUUID();
		playerService.recordConnection(ownerUuid, "Bichou");
		bearerToken = "Bearer " + jwtService.issueAccessToken(ownerUuid, "Bichou");
	}

	private String validCreateBody(UUID requestUuid) {
		return """
				{
				  "request_uuid": "%s",
				  "species": "pikachu",
				  "level": 50,
				  "nature": "timid",
				  "ability": "static",
				  "cobblemon_data_version": "1.8.1",
				  "data": {
				    "ivs": {"hp":31,"atk":31,"def":31,"spa":31,"spd":31,"spe":31},
				    "evs": {"hp":0,"atk":252,"def":0,"spa":0,"spd":4,"spe":252},
				    "moves": ["thunderbolt"]
				  }
				}
				""".formatted(requestUuid);
	}

	@Test
	void createReturns201AndPersistsPokemon() throws Exception {
		mockMvc.perform(post("/pokemon")
						.header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validCreateBody(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.species").value("pikachu"))
				.andExpect(jsonPath("$.owner_uuid").value(ownerUuid.toString()))
				.andExpect(jsonPath("$.box_id").value(1))
				.andExpect(jsonPath("$.box_slot").value(1));
	}

	@Test
	void createTwiceWithSameRequestUuidDoesNotDuplicate() throws Exception {
		UUID requestUuid = UUID.randomUUID();

		mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(requestUuid)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(requestUuid)))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/players/" + ownerUuid + "/pokemon").header("Authorization", bearerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void createRejectsIllegalEvs() throws Exception {
		String body = """
				{
				  "request_uuid": "%s",
				  "species": "pikachu",
				  "level": 50,
				  "nature": "timid",
				  "ability": "static",
				  "cobblemon_data_version": "1.8.1",
				  "data": {
				    "ivs": {"hp":31},
				    "evs": {"hp":252,"atk":252,"def":10},
				    "moves": []
				  }
				}
				""".formatted(UUID.randomUUID());

		mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error_code").value("ERROR_LEGALITY_EV_TOTAL_EXCEEDED"));
	}

	@Test
	void createWithoutAuthenticationIsRejected() throws Exception {
		mockMvc.perform(post("/pokemon")
						.contentType(MediaType.APPLICATION_JSON)
						.content(validCreateBody(UUID.randomUUID())))
				.andExpect(status().isForbidden());
	}

	@Test
	void listingAnotherPlayersPokemonIsForbidden() throws Exception {
		mockMvc.perform(get("/players/" + UUID.randomUUID() + "/pokemon").header("Authorization", bearerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_OWNERSHIP_MISMATCH"));
	}

	@Test
	void updateDeleteAndCloneRoundTrip() throws Exception {
		String createResponse = mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String pokemonUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"level\": 60}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.level").value(60));

		mockMvc.perform(post("/pokemon/" + pokemonUuid + "/clone").header("Authorization", bearerToken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.box_slot").value(2));

		mockMvc.perform(delete("/pokemon/" + pokemonUuid).header("Authorization", bearerToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/players/" + ownerUuid + "/pokemon").header("Authorization", bearerToken))
				.andExpect(jsonPath("$.length()").value(1));
	}
}
