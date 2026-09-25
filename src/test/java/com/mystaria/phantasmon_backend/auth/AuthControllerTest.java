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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
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
								{"uuid":"%s","username":"Bichou","server_id":"abc123"}
								""".formatted(uuid)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.refresh_token").isNotEmpty())
				.andExpect(jsonPath("$.expires_in").isNumber());

		assertThat(playerRepository.findById(uuid)).isPresent();
		assertThat(playerRepository.findById(uuid).get().getLastUsername()).isEqualTo("Bichou");
	}

	@Test
	void authenticateReturns401_whenMojangDoesNotVerify() throws Exception {
		when(mojangSessionClient.hasJoined(anyString(), anyString(), any())).thenReturn(Optional.empty());

		mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"Bichou","server_id":"abc123"}
								""".formatted(UUID.randomUUID())))
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
								{"uuid":"%s","username":"Bichou","server_id":"abc123"}
								""".formatted(claimedUuid)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error_code").value("ERROR_AUTH_UUID_MISMATCH"));
	}

	@Test
	void authenticateRejectsBlankUsername() throws Exception {
		mockMvc.perform(post("/auth/session")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"uuid":"%s","username":"","server_id":"abc123"}
								""".formatted(UUID.randomUUID())))
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
								{"uuid":"%s","username":"Bichou","server_id":"abc123"}
								""".formatted(uuid)))
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

	private static String dashless(UUID uuid) {
		return uuid.toString().replace("-", "");
	}
}
