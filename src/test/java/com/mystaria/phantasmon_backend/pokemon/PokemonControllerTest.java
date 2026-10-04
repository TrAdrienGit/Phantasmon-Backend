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
	void anOverlongIdentifierIsRefusedAsABadRequestNotAServerError() throws Exception {
		// SEC-5: species is VARCHAR(64); a longer value used to fail in the database with a 500.
		mockMvc.perform(post("/pokemon")
						.header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(validCreateBody(UUID.randomUUID()).replace("\"pikachu\"", "\"" + "p".repeat(65) + "\"")))
				.andExpect(status().isBadRequest());
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

	@Test
	void natureAbilityAndShinyCanBeUpdated() throws Exception {
		String createResponse = mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String pokemonUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nature\": \"jolly\", \"ability\": \"intimidate\", \"is_shiny\": true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nature").value("jolly"))
				.andExpect(jsonPath("$.ability").value("intimidate"))
				.andExpect(jsonPath("$.is_shiny").value(true));

		// A plain omitted field must leave it untouched (partial-update convention).
		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"level\": 42}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nature").value("jolly"))
				.andExpect(jsonPath("$.is_shiny").value(true));
	}

	@Test
	void assigningToTeamClearsPcLocationNoDuplication() throws Exception {
		String createResponse = mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String pokemonUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");
		// Auto-assigned to PC box 1 slot 1 by create() — confirm the starting point.
		com.jayway.jsonpath.JsonPath.read(createResponse, "$.box_id");

		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"team_slot\": 3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.team_slot").value(3))
				.andExpect(jsonPath("$.box_id").doesNotExist())
				.andExpect(jsonPath("$.box_slot").doesNotExist());

		// A plain omitted team_slot must leave it untouched (partial-update convention).
		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"level\": 55}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.team_slot").value(3));

		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"box_id\": 1, \"box_slot\": 1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.team_slot").doesNotExist())
				.andExpect(jsonPath("$.box_id").value(1))
				.andExpect(jsonPath("$.box_slot").value(1));
	}

	@Test
	void incompleteBoxDestinationIsRejected() throws Exception {
		String pokemonUuid = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
								.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
						.andReturn().getResponse().getContentAsString(),
				"$.uuid");

		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"box_id\": 2}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error_code").value("ERROR_POKEMON_INCOMPLETE_BOX_DESTINATION"));
	}

	/** Uniform drag&drop semantics (Adrien 2026-09-27): occupied destination swaps, regardless of PC/team mix. */
	@Test
	void movingPcPokemonOntoOccupiedTeamSlotSwapsBothWays() throws Exception {
		String teamMemberUuid = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
								.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
						.andReturn().getResponse().getContentAsString(),
				"$.uuid");
		mockMvc.perform(patch("/pokemon/" + teamMemberUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\": 1}"))
				.andExpect(status().isOk());

		String createPcResponse = mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String pcMonUuid = com.jayway.jsonpath.JsonPath.read(createPcResponse, "$.uuid");
		int pcMonBoxId = com.jayway.jsonpath.JsonPath.read(createPcResponse, "$.box_id");
		int pcMonBoxSlot = com.jayway.jsonpath.JsonPath.read(createPcResponse, "$.box_slot");

		mockMvc.perform(patch("/pokemon/" + pcMonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\": 1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.team_slot").value(1))
				.andExpect(jsonPath("$.box_id").doesNotExist());

		// The bumped team member must land exactly where the PC Pokémon used to be — a swap, not a discard.
		mockMvc.perform(get("/players/" + ownerUuid + "/pokemon").header("Authorization", bearerToken))
				.andExpect(jsonPath("$[?(@.uuid == '" + teamMemberUuid + "')].box_id").value(pcMonBoxId))
				.andExpect(jsonPath("$[?(@.uuid == '" + teamMemberUuid + "')].box_slot").value(pcMonBoxSlot));
	}

	@Test
	void movingBetweenPcSlotsSwapsWithTheOccupant() throws Exception {
		String createFirstResponse = mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String firstUuid = com.jayway.jsonpath.JsonPath.read(createFirstResponse, "$.uuid");

		String createSecondResponse = mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String secondUuid = com.jayway.jsonpath.JsonPath.read(createSecondResponse, "$.uuid");
		int secondBoxId = com.jayway.jsonpath.JsonPath.read(createSecondResponse, "$.box_id");
		int secondBoxSlot = com.jayway.jsonpath.JsonPath.read(createSecondResponse, "$.box_slot");

		// Move first onto second's PC slot -> they swap.
		mockMvc.perform(patch("/pokemon/" + firstUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"box_id\": " + secondBoxId + ", \"box_slot\": " + secondBoxSlot + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.box_id").value(secondBoxId))
				.andExpect(jsonPath("$.box_slot").value(secondBoxSlot));

		mockMvc.perform(get("/players/" + ownerUuid + "/pokemon").header("Authorization", bearerToken))
				.andExpect(jsonPath("$[?(@.uuid == '" + secondUuid + "')].box_id").value(1))
				.andExpect(jsonPath("$[?(@.uuid == '" + secondUuid + "')].box_slot").value(1));
	}

	@Test
	void movingBetweenTeamSlotsSwapsWithTheOccupant() throws Exception {
		String firstUuid = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
								.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
						.andReturn().getResponse().getContentAsString(),
				"$.uuid");
		String secondUuid = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
								.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
						.andReturn().getResponse().getContentAsString(),
				"$.uuid");

		mockMvc.perform(patch("/pokemon/" + firstUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\": 1}"))
				.andExpect(status().isOk());
		mockMvc.perform(patch("/pokemon/" + secondUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\": 2}"))
				.andExpect(status().isOk());

		// first (slot 1) moves onto second's slot (2) -> they swap.
		mockMvc.perform(patch("/pokemon/" + firstUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\": 2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.team_slot").value(2));

		mockMvc.perform(get("/players/" + ownerUuid + "/pokemon").header("Authorization", bearerToken))
				.andExpect(jsonPath("$[?(@.uuid == '" + secondUuid + "')].team_slot").value(1));
	}

	@Test
	void movingOwnPokemonOntoItsOwnCurrentSlotIsANoOpNotASelfSwap() throws Exception {
		String pokemonUuid = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(post("/pokemon").header("Authorization", bearerToken)
								.contentType(MediaType.APPLICATION_JSON).content(validCreateBody(UUID.randomUUID())))
						.andReturn().getResponse().getContentAsString(),
				"$.uuid");
		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\": 1}"))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/pokemon/" + pokemonUuid).header("Authorization", bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"team_slot\": 1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.team_slot").value(1));
	}
}
