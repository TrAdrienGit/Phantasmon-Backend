package com.mystaria.phantasmon_backend.hub;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-41: a hub of a kind whose add-on is not loaded (Phantasmon Events' mirror hubs, Events uninstalled) stays in the
 * database but is hidden, its anchors too, so the core never treats it as an ordinary hub.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "phantasmon.logging.enabled=false", "phantasmon.admin.file=build/test-admins.txt" })
@AutoConfigureMockMvc
class UnavailableHubKindTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private HubRepository hubRepository;

	@Autowired
	private HubAnchorRepository anchorRepository;

	private Hub hub;

	@AfterEach
	void cleanUp() {
		if (hub != null) {
			anchorRepository.deleteAll(anchorRepository.findByHubUuid(hub.getUuid()));
			hubRepository.delete(hub);
		}
	}

	@Test
	void aHubOfAMissingAddOnIsHiddenWithItsAnchors() throws Exception {
		UUID player = UUID.randomUUID();
		String name = "Pl" + player.toString().substring(0, 8);
		playerService.recordConnection(player, name);
		String token = "Bearer " + jwtService.issueAccessToken(player, name);
		String server = "srv-" + UUID.randomUUID();
		hub = hubRepository.saveAndFlush(new Hub(UUID.randomUUID(), "t_" + UUID.randomUUID().toString().substring(0, 8),
				"MIRROR", 11, 384, 11, null));
		anchorRepository.saveAndFlush(new HubAnchor(UUID.randomUUID(), player, hub.getUuid(), "source", server,
				"minecraft:overworld", 0.5, -64, 0.5, (short) 0, "SOURCE"));

		mockMvc.perform(get("/hubs").header("Authorization", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].name", not(hasItem(hub.getName()))))
				.andExpect(jsonPath("$[*].name", hasItem("global")));
		mockMvc.perform(get("/hub/anchors").header("Authorization", token)
						.param("server_fingerprint", server).param("dimension", "minecraft:overworld"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/hub/anchors/mine").header("Authorization", token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}
}
