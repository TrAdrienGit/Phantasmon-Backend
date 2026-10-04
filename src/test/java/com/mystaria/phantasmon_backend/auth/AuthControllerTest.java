package com.mystaria.phantasmon_backend.auth;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.player.PlayerRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@AutoConfigureMockMvc
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PlayerRepository playerRepository;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MojangSessionClient mojangSessionClient;

	@Test
	void authenticateReturnsTokensAndPersistsPlayer_whenMojangVerifies() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(mojangSessionClient.hasJoined(anyString(), anyString(), any()))
				.thenReturn(Optional.of(new MojangProfile(dashless(uuid), "Bichou")));

		mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"Bichou","server_id":"%s"}
								""".formatted(uuid, challenge())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.refresh_token").isNotEmpty())
				.andExpect(jsonPath("$.expires_in").isNumber());

		assertThat(playerRepository.findById(uuid)).isPresent();
		assertThat(playerRepository.findById(uuid).get().getLastUsername()).isEqualTo("Bichou");
	}

	@Test
	void eachChallengeIsDifferent() throws Exception {
		assertThat(challenge()).isNotBlank().isNotEqualTo(challenge());
	}

	@Test
	void authenticateRejectsAServerIdTheBackendNeverIssued_withoutAskingMojang() throws Exception {
		// SEC-1: a third-party Minecraft server replaying a player's join proof only knows its own serverId.
		when(mojangSessionClient.hasJoined(anyString(), anyString(), any()))
				.thenReturn(Optional.of(new MojangProfile(dashless(UUID.randomUUID()), "Bichou")));

		mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"Bichou","server_id":"-4f2a9c3b1d0e7a6b5c4d3e2f1a0b9c8d7e6f5a4b"}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_INVALID_CHALLENGE"));

		verify(mojangSessionClient, never()).hasJoined(anyString(), anyString(), any());
	}

	@Test
	void aChallengeCanOnlyBeUsedOnce() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(mojangSessionClient.hasJoined(anyString(), anyString(), any()))
				.thenReturn(Optional.of(new MojangProfile(dashless(uuid), "Bichou")));
		String body = """
				{"uuid":"%s","username":"Bichou","server_id":"%s"}
				""".formatted(uuid, challenge());

		mockMvc.perform(post("/auth/session").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
		mockMvc.perform(post("/auth/session").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_INVALID_CHALLENGE"));
	}

	@Test
	void authenticateReturns401_whenMojangDoesNotVerify() throws Exception {
		when(mojangSessionClient.hasJoined(anyString(), anyString(), any())).thenReturn(Optional.empty());

		mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"Bichou","server_id":"%s"}
								""".formatted(UUID.randomUUID(), challenge())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_MOJANG_VERIFICATION_FAILED"));
	}

	@Test
	void authenticateReturns401_whenClaimedUuidDoesNotMatchMojangProfile() throws Exception {
		UUID claimedUuid = UUID.randomUUID();
		UUID mojangUuid = UUID.randomUUID();
		when(mojangSessionClient.hasJoined(anyString(), anyString(), any()))
				.thenReturn(Optional.of(new MojangProfile(dashless(mojangUuid), "Bichou")));

		mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"Bichou","server_id":"%s"}
								""".formatted(claimedUuid, challenge())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_UUID_MISMATCH"));
	}

	@Test
	void authenticateRejectsBlankUsername() throws Exception {
		mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"","server_id":"%s"}
								""".formatted(UUID.randomUUID(), challenge())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void refreshReturnsNewTokens_whenRefreshTokenIsValid() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(mojangSessionClient.hasJoined(anyString(), anyString(), any()))
				.thenReturn(Optional.of(new MojangProfile(dashless(uuid), "Bichou")));

		String sessionResponseJson = mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"Bichou","server_id":"%s"}
								""".formatted(uuid, challenge())))
				.andReturn().getResponse().getContentAsString();
		String refreshToken = com.jayway.jsonpath.JsonPath.read(sessionResponseJson, "$.refresh_token");

		mockMvc.perform(post("/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.refresh_token").isNotEmpty())
				.andExpect(jsonPath("$.expires_in").isNumber());
	}

	@Test
	void refreshReturns401_whenGivenAnAccessTokenInstead() throws Exception {
		String accessToken = jwtService.issueAccessToken(UUID.randomUUID(), "Bichou");

		mockMvc.perform(post("/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(accessToken))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_INVALID_REFRESH_TOKEN"));
	}

	@Test
	void refreshReturns401_whenTokenIsGarbage() throws Exception {
		mockMvc.perform(post("/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest("not-a-real-token"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_INVALID_REFRESH_TOKEN"));
	}

	@Test
	void refreshReturns401_whenPlayerNoLongerExists() throws Exception {
		String refreshToken = jwtService.issueRefreshToken(UUID.randomUUID());

		mockMvc.perform(post("/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_INVALID_REFRESH_TOKEN"));
	}

	/** A fresh one-time challenge from the backend, used as the Mojang {@code serverId} (SEC-1). */
	private String challenge() throws Exception {
		String body = mockMvc.perform(post("/auth/challenge"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("challenge").asString();
	}

	private static String dashless(UUID uuid) {
		return uuid.toString().replace("-", "");
	}
}
