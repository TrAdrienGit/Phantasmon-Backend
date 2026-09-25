package com.mystaria.phantasmon_backend.trade;

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
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@AutoConfigureMockMvc
class TradeControllerTest {

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
				(short) 50, "timid", "static", false, (short) 1, (short) 1, null, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of())));
		bobMon = pokemonRepository.saveAndFlush(new Pokemon(UUID.randomUUID(), bobUuid, "charmander", null,
				(short) 50, "adamant", "blaze", false, (short) 1, (short) 1, null, "1.8.1",
				Map.of("ivs", Map.of(), "evs", Map.of())));
	}

	private String proposeBody(UUID requestUuid) {
		return """
				{"request_uuid":"%s","recipient_uuid":"%s","offered_pokemon_uuid":"%s","requested_pokemon_uuid":"%s"}
				""".formatted(requestUuid, bobUuid, aliceMon.getUuid(), bobMon.getUuid());
	}

	@Test
	void proposeCreatesPendingTrade() throws Exception {
		mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.initiator_uuid").value(aliceUuid.toString()))
				.andExpect(jsonPath("$.recipient_uuid").value(bobUuid.toString()));
	}

	@Test
	void proposeTwiceWithSameRequestUuidDoesNotDuplicate() throws Exception {
		UUID requestUuid = UUID.randomUUID();

		mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(requestUuid)))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(requestUuid)))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/players/" + aliceUuid + "/trades").header("Authorization", aliceToken))
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void proposeWithPokemonNotOwnedByInitiatorIsForbidden() throws Exception {
		String body = """
				{"request_uuid":"%s","recipient_uuid":"%s","offered_pokemon_uuid":"%s","requested_pokemon_uuid":"%s"}
				""".formatted(UUID.randomUUID(), bobUuid, bobMon.getUuid(), bobMon.getUuid());

		mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_OWNERSHIP_MISMATCH"));
	}

	@Test
	void proposeWithRequestedPokemonNotOwnedByRecipientFails() throws Exception {
		String body = """
				{"request_uuid":"%s","recipient_uuid":"%s","offered_pokemon_uuid":"%s","requested_pokemon_uuid":"%s"}
				""".formatted(UUID.randomUUID(), bobUuid, aliceMon.getUuid(), aliceMon.getUuid());

		mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_TRADE_INVALID_RECIPIENT_POKEMON"));
	}

	@Test
	void acceptSwapsOwnershipAtomically() throws Exception {
		String createResponse = mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String tradeUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		mockMvc.perform(post("/trades/" + tradeUuid + "/accept").header("Authorization", bobToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Pokemon reloadedAliceMon = pokemonRepository.findById(aliceMon.getUuid()).orElseThrow();
		Pokemon reloadedBobMon = pokemonRepository.findById(bobMon.getUuid()).orElseThrow();
		assertThat(reloadedAliceMon.getOwnerUuid()).isEqualTo(bobUuid);
		assertThat(reloadedBobMon.getOwnerUuid()).isEqualTo(aliceUuid);
	}

	@Test
	void onlyRecipientCanAccept() throws Exception {
		String createResponse = mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String tradeUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		mockMvc.perform(post("/trades/" + tradeUuid + "/accept").header("Authorization", aliceToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_OWNERSHIP_MISMATCH"));
	}

	@Test
	void cancelByEitherPartyMarksTradeCancelledAndBlocksFurtherAccept() throws Exception {
		String createResponse = mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String tradeUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		mockMvc.perform(post("/trades/" + tradeUuid + "/cancel").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		mockMvc.perform(post("/trades/" + tradeUuid + "/accept").header("Authorization", bobToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_TRADE_INVALID_STATE"));

		Pokemon reloadedAliceMon = pokemonRepository.findById(aliceMon.getUuid()).orElseThrow();
		assertThat(reloadedAliceMon.getOwnerUuid()).isEqualTo(aliceUuid);
	}

	@Test
	void acceptFailsAndCancelsTradeIfOwnershipChangedSincePropose() throws Exception {
		String createResponse = mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String tradeUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		// Alice's offered pokemon changes owner behind the trade's back.
		UUID thirdPartyUuid = UUID.randomUUID();
		playerService.recordConnection(thirdPartyUuid, "Carol");
		aliceMon.setOwnerUuid(thirdPartyUuid);
		pokemonRepository.saveAndFlush(aliceMon);

		mockMvc.perform(post("/trades/" + tradeUuid + "/accept").header("Authorization", bobToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_TRADE_OWNERSHIP_CHANGED"));

		mockMvc.perform(get("/trades/" + tradeUuid).header("Authorization", bobToken))
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void proposingTradeWithSelfIsRejected() throws Exception {
		String body = """
				{"request_uuid":"%s","recipient_uuid":"%s","offered_pokemon_uuid":"%s","requested_pokemon_uuid":"%s"}
				""".formatted(UUID.randomUUID(), aliceUuid, aliceMon.getUuid(), aliceMon.getUuid());

		mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_TRADE_SELF"));
	}

	@Test
	void strangerCannotSeeSomeoneElsesTrade() throws Exception {
		String createResponse = mockMvc.perform(post("/trades").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content(proposeBody(UUID.randomUUID())))
				.andReturn().getResponse().getContentAsString();
		String tradeUuid = com.jayway.jsonpath.JsonPath.read(createResponse, "$.uuid");

		UUID strangerUuid = UUID.randomUUID();
		playerService.recordConnection(strangerUuid, "Stranger");
		String strangerToken = "Bearer " + jwtService.issueAccessToken(strangerUuid, "Stranger");

		mockMvc.perform(get("/trades/" + tradeUuid).header("Authorization", strangerToken))
				.andExpect(status().isForbidden());
	}
}
