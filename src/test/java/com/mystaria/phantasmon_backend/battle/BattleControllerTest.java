package com.mystaria.phantasmon_backend.battle;

import java.util.List;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST read access to battle sessions. Sessions are only ever created by the live battle flow over the WebSocket
 * ({@code LiveBattleWebSocketIntegrationTest}); the old {@code POST /battles} and {@code POST /battles/{uuid}/result}
 * routes were removed (SEC-3): they let a player open a battle against anyone without consent and let any
 * participant — the guest of a live battle included — declare the winner.
 */
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
	private BattleRepository battleRepository;

	private UUID aliceUuid;
	private UUID bobUuid;
	private String aliceToken;
	private BattleSession hosted;

	@BeforeEach
	void setUp() {
		aliceUuid = UUID.randomUUID();
		bobUuid = UUID.randomUUID();
		playerService.recordConnection(aliceUuid, "Alice");
		playerService.recordConnection(bobUuid, "Bob");
		aliceToken = "Bearer " + jwtService.issueAccessToken(aliceUuid, "Alice");
		hosted = battleRepository.saveAndFlush(BattleSession.hosted(UUID.randomUUID(), aliceUuid, bobUuid,
				List.of(UUID.randomUUID()), List.of(UUID.randomUUID())));
	}

	@Test
	void aParticipantCanViewTheBattle() throws Exception {
		mockMvc.perform(get("/battles/" + hosted.getUuid()).header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void aStrangerCannotViewSomeoneElsesBattle() throws Exception {
		UUID strangerUuid = UUID.randomUUID();
		playerService.recordConnection(strangerUuid, "Stranger");
		String strangerToken = "Bearer " + jwtService.issueAccessToken(strangerUuid, "Stranger");

		mockMvc.perform(get("/battles/" + hosted.getUuid()).header("Authorization", strangerToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void battlesCanNoLongerBeOpenedOrSettledOverRest() throws Exception {
		long before = battleRepository.count();
		mockMvc.perform(post("/battles").header("Authorization", aliceToken).contentType(MediaType.APPLICATION_JSON)
						.content("{\"request_uuid\":\"%s\",\"opponent_uuid\":\"%s\",\"team\":[]}".formatted(UUID.randomUUID(), bobUuid)))
				.andExpect(status().is4xxClientError());
		mockMvc.perform(post("/battles/" + hosted.getUuid() + "/result").header("Authorization", aliceToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"winner_uuid\":\"%s\"}".formatted(aliceUuid)))
				.andExpect(status().is4xxClientError());

		assertThat(battleRepository.count()).isEqualTo(before);
		assertThat(battleRepository.findById(hosted.getUuid()).orElseThrow().getStatus()).isEqualTo(BattleStatus.ACTIVE);
	}
}
