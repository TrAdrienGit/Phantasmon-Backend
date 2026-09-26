package com.mystaria.phantasmon_backend.battle;

import java.util.Map;
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
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@AutoConfigureMockMvc
class BattleControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private PokemonRepository pokemonRepository;

	private UUID aliceUuid;
	private UUID bobUuid;
	private String aliceToken;
	private String bobToken;
	private Pokemon aliceMon;
	private Pokemon bobMon;

	@BeforeEach
	void setUp() {
		aliceUuid = UUID.randomUUID();
		bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		aliceToken = "Bearer " + jwtService.issueAccessToken(aliceUuid, "Alice");
		bobToken = "Bearer " + jwtService.issueAccessToken(bobUuid, "Bob");

		aliceMon = pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), aliceUuid, "pikachu", null,
				(short) 50, "timid", "static", false, null, null, (short) 1, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of())));
		bobMon = pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), bobUuid, "charmander", null,
				(short) 50, "adamant", "blaze", false, null, null, (short) 1, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	private String createBody(UUID requestUuid, UUID opponentUuid, UUID... team) {
		String teamJson = String.join(",", java.util.Arrays.stream(team).map(u -> "\"" + u + "\"").toList());
		return """
				{"request_uuid":"%s","opponent_uuid":"%s","team":[%s]}
				""".formatted(requestUuid, opponentUuid, teamJson);
	}

	@Test
	void createStartsActiveBattleWithBothTeamsSnapshotted() throws Exception {
		mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), bobUuid, aliceMon.getUuid())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.player_a").value(aliceUuid.toString()))
				.andExpect(jsonPath("$.player_b").value(bobUuid.toString()))
				.andExpect(jsonPath("$.team_a[0]").value(aliceMon.getUuid().toString()))
				.andExpect(jsonPath("$.team_b[0]").value(bobMon.getUuid().toString()));
	}

	@Test
	void createTwiceWithSameRequestUuidDoesNotDuplicate() throws Exception {
		UUID requestUuid = UUID.randomUUID();
		String firstUuid = mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(requestUuid, bobUuid, aliceMon.getUuid())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String secondUuid = mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(requestUuid, bobUuid, aliceMon.getUuid())))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		org.assertj.core.api.Assertions.assertThat(firstUuid).isEqualTo(secondUuid);
	}

	@Test
	void createAgainstSelfIsRejected() throws Exception {
		mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), aliceUuid, aliceMon.getUuid())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_BATTLE_SELF"));
	}

	@Test
	void createAgainstUnknownPlayerFails() throws Exception {
		mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), UUID.randomUUID(), aliceMon.getUuid())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_PLAYER_NOT_FOUND"));
	}

	@Test
	void createWithPokemonNotOwnedByInitiatorIsForbidden() throws Exception {
		mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), bobUuid, bobMon.getUuid())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_OWNERSHIP_MISMATCH"));
	}

	@Test
	void createWhenOpponentHasNoActiveTeamFails() throws Exception {
		UUID carolUuid = UUID.randomUUID();
		playerService.recordConnection(carolUuid, "Carol");

		mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), carolUuid, aliceMon.getUuid())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_BATTLE_OPPONENT_NO_TEAM"));
	}

	@Test
	void submitResultFinishesBattleWhenWinnerIsParticipant() throws Exception {
		String createResponse = mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), bobUuid, aliceMon.getUuid())))
				.andReturn().getResponse().getContentAsString();
		String battleUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		mockMvc.perform(post("/battles/" + battleUuid + "/result").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"winner_uuid":"%s","log":{"turns":3}}
								""".formatted(aliceUuid)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FINISHED"))
				.andExpect(jsonPath("$.result.winner_uuid").value(aliceUuid.toString()));
	}

	@Test
	void submitResultWithWinnerNotInBattleIsRejected() throws Exception {
		String createResponse = mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), bobUuid, aliceMon.getUuid())))
				.andReturn().getResponse().getContentAsString();
		String battleUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		mockMvc.perform(post("/battles/" + battleUuid + "/result").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"winner_uuid":"%s"}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error_code").value("ERROR_BATTLE_INVALID_RESULT"));
	}

	@Test
	void submitResultTwiceIsRejectedOnceFinished() throws Exception {
		String createResponse = mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), bobUuid, aliceMon.getUuid())))
				.andReturn().getResponse().getContentAsString();
		String battleUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");
		String resultBody = """
				{"winner_uuid":"%s"}
				""".formatted(aliceUuid);

		mockMvc.perform(post("/battles/" + battleUuid + "/result").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(resultBody))
				.andExpect(status().isOk());
		mockMvc.perform(post("/battles/" + battleUuid + "/result").header("Authorization", bobToken)
						.contentType(MediaType.APPLICATION_JSON).content(resultBody))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_BATTLE_INVALID_STATE"));
	}

	@Test
	void strangerCannotViewOrSubmitResultForSomeoneElsesBattle() throws Exception {
		String createResponse = mockMvc.perform(post("/battles").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(createBody(UUID.randomUUID(), bobUuid, aliceMon.getUuid())))
				.andReturn().getResponse().getContentAsString();
		String battleUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		UUID strangerUuid = UUID.randomUUID();
		playerService.recordConnection(strangerUuid, "Stranger");
		String strangerToken = "Bearer " + jwtService.issueAccessToken(strangerUuid, "Stranger");

		mockMvc.perform(get("/battles/" + battleUuid).header("Authorization", strangerToken))
				.andExpect(status().isForbidden());
	}
}
