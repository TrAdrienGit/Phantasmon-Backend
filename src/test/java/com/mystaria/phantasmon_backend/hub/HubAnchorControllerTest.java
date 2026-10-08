package com.mystaria.phantasmon_backend.hub;

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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phantasmon Network — Hub Anchors (network-cahier-des-charges.md §5.2, D-28, D-30): any player may create one
 * anchor, a 21×21×21 cube, shared by the players of its server (listed per server and dimension, never across servers),
 * named uniquely per server; the owner or an admin deletes it.
 */
@Import(TestcontainersConfiguration.class)
// Same properties as AdminControllerTest, so both share one cached Spring context (and one admin file).
@SpringBootTest(properties = { "phantasmon.logging.enabled=false", "phantasmon.admin.file=build/test-admins.txt" })
@AutoConfigureMockMvc
class HubAnchorControllerTest {

	private static final Path ADMIN_FILE = Path.of("build/test-admins.txt");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	private String server;
	private String aliceToken;
	private String bobToken;
	private String adminToken;
	private UUID aliceUuid;

	@BeforeEach
	void setUp() throws Exception {
		// One fresh server per test: anchor names are unique per server, and the database outlives each test.
		server = "srv-" + UUID.randomUUID();
		aliceUuid = UUID.randomUUID();
		aliceToken = tokenFor(aliceUuid, "Al");
		bobToken = tokenFor(UUID.randomUUID(), "Bo");
		UUID adminUuid = UUID.randomUUID();
		String adminName = "Op" + adminUuid.toString().substring(0, 8);
		adminToken = tokenFor(adminUuid, adminName);
		Files.createDirectories(ADMIN_FILE.getParent());
		Files.writeString(ADMIN_FILE, adminName + "\n");
		Files.setLastModifiedTime(ADMIN_FILE, FileTime.from(Instant.now().plusSeconds((long) (Math.random() * 1000) + 1)));
	}

	private String tokenFor(UUID uuid, String prefix) {
		String name = prefix.length() > 2 ? prefix : prefix + uuid.toString().substring(0, 8);
		playerService.recordConnection(uuid, name);
		return "Bearer " + jwtService.issueAccessToken(uuid, name);
	}

	private static String body(UUID requestUuid, String name, String server, String dimension, double yaw) {
		return """
				{"request_uuid":"%s","name":"%s","server_fingerprint":"%s","dimension":"%s",
				 "origin":{"x":120.5,"y":64.0,"z":-33.5},"yaw":%s}
				""".formatted(requestUuid, name, server, dimension, yaw);
	}

	private ResultActions create(String token, String name, String server, double yaw) throws Exception {
		return create(token, UUID.randomUUID(), name, server, "minecraft:overworld", yaw);
	}

	private ResultActions create(String token, UUID requestUuid, String name, String server, String dimension, double yaw)
			throws Exception {
		return mockMvc.perform(post("/hub/anchors").header("Authorization", token)
				.contentType(MediaType.APPLICATION_JSON).content(body(requestUuid, name, server, dimension, yaw)));
	}

	private static String uuidOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.uuid");
	}

	@Test
	void anyPlayerCreatesAnAnchorWhoseYawIsSnappedToAQuarterTurn() throws Exception {
		create(aliceToken, "Place du marche", server, 100.0)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.uuid").isNotEmpty())
				.andExpect(jsonPath("$.owner_uuid").value(aliceUuid.toString()))
				.andExpect(jsonPath("$.name").value("Place du marche"))
				.andExpect(jsonPath("$.server_fingerprint").value(server))
				.andExpect(jsonPath("$.dimension").value("minecraft:overworld"))
				.andExpect(jsonPath("$.origin.x").value(120.5))
				.andExpect(jsonPath("$.origin.y").value(64.0))
				.andExpect(jsonPath("$.origin.z").value(-33.5))
				.andExpect(jsonPath("$.yaw").value(90))
				.andExpect(jsonPath("$.size").value(21))
				.andExpect(jsonPath("$.created_at").isNotEmpty());

		create(bobToken, "Arene", server, -100.0).andExpect(jsonPath("$.yaw").value(270));
	}

	@Test
	void replayingTheSameRequestReturnsTheSameAnchorWithoutCreatingAnother() throws Exception {
		UUID requestUuid = UUID.randomUUID();
		String first = uuidOf(create(aliceToken, requestUuid, "Hub", server, "minecraft:overworld", 0).andExpect(status().isCreated()));

		create(aliceToken, requestUuid, "Hub", server, "minecraft:overworld", 0)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.uuid").value(first));
	}

	@Test
	void aPlayerHasOneAnchorAtMost() throws Exception {
		String first = uuidOf(create(aliceToken, "Hub", server, 0).andExpect(status().isCreated()));

		create(aliceToken, "Second", "srv-" + UUID.randomUUID(), 0)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_ANCHOR_QUOTA"))
				.andExpect(jsonPath("$.details.anchor_uuid").value(first));
	}

	@Test
	void namesAreUniquePerServerIgnoringCase() throws Exception {
		create(aliceToken, "Spawn", server, 0).andExpect(status().isCreated());

		create(bobToken, "SPAWN", server, 0)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_ANCHOR_NAME_TAKEN"));
		create(bobToken, "Spawn", "srv-" + UUID.randomUUID(), 0).andExpect(status().isCreated());
	}

	@Test
	void malformedAnchorsAreRefused() throws Exception {
		create(aliceToken, "ab", server, 0)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ERROR_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.fields[0]").value("name"));
		create(aliceToken, " Spawn", server, 0).andExpect(jsonPath("$.details.fields[0]").value("name"));
		create(aliceToken, "Spawn<script>", server, 0).andExpect(jsonPath("$.details.fields[0]").value("name"));
		create(aliceToken, "Spawn", "", 0).andExpect(jsonPath("$.details.fields[0]").value("server_fingerprint"));

		mockMvc.perform(post("/hub/anchors").header("Authorization", aliceToken).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"request_uuid":"%s","name":"Spawn","server_fingerprint":"%s","dimension":"minecraft:overworld",
								 "origin":{"x":40000000,"y":64}}
								""".formatted(UUID.randomUUID(), server)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.fields", containsInAnyOrder("origin.x", "origin.z", "yaw")));
	}

	@Test
	void theAnchorsOfAServerAreListedToItsPlayersButNotAcrossServers() throws Exception {
		create(aliceToken, UUID.randomUUID(), "Overworld", server, "minecraft:overworld", 0);
		create(bobToken, UUID.randomUUID(), "Nether", server, "minecraft:the_nether", 0);
		create(tokenFor(UUID.randomUUID(), "Ca"), "Elsewhere", "srv-" + UUID.randomUUID(), 0);

		mockMvc.perform(get("/hub/anchors").header("Authorization", bobToken)
						.param("server_fingerprint", server).param("dimension", "minecraft:overworld"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Overworld"));

		mockMvc.perform(get("/hub/anchors").header("Authorization", bobToken).param("server_fingerprint", server))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ERROR_MALFORMED_REQUEST"));
	}

	@Test
	void aPlayerFindsTheirOwnAnchor() throws Exception {
		mockMvc.perform(get("/hub/anchors/mine").header("Authorization", aliceToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_ANCHOR_NOT_FOUND"));

		create(aliceToken, "Hub", server, 0);

		mockMvc.perform(get("/hub/anchors/mine").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Hub"));
	}

	@Test
	void onlyTheOwnerOrAnAdminDeletesAnAnchor() throws Exception {
		String anchor = uuidOf(create(aliceToken, "Hub", server, 0));

		mockMvc.perform(delete("/hub/anchors/" + anchor).header("Authorization", bobToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_ANCHOR_FORBIDDEN"));
		mockMvc.perform(delete("/hub/anchors/" + anchor).header("Authorization", aliceToken))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/hub/anchors/" + anchor).header("Authorization", aliceToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_ANCHOR_NOT_FOUND"));

		// Once deleted, the owner may place their anchor elsewhere — and an admin may remove anyone's.
		String moved = uuidOf(create(aliceToken, "Hub", server, 0).andExpect(status().isCreated()));
		mockMvc.perform(delete("/hub/anchors/" + moved).header("Authorization", adminToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void theGlobalHubSchematicIsDescribedThenServedWhole() throws Exception {
		byte[] file = Files.readAllBytes(Path.of("src/test/resources/hub_schematics/hub_global/test_hub.schem"));
		mockMvc.perform(get("/hub/schematic").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("test_hub.schem"))
				.andExpect(jsonPath("$.format").value("SCHEM"))
				.andExpect(jsonPath("$.size.x").value(21))
				.andExpect(jsonPath("$.size.y").value(21))
				.andExpect(jsonPath("$.size.z").value(21))
				.andExpect(jsonPath("$.bytes").value(file.length))
				.andExpect(jsonPath("$.sha256").value(java.util.HexFormat.of().formatHex(
						java.security.MessageDigest.getInstance("SHA-256").digest(file))));
		byte[] served = mockMvc.perform(get("/hub/schematic/file").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();
		org.assertj.core.api.Assertions.assertThat(served).isEqualTo(file);
		mockMvc.perform(get("/hub/schematic")).andExpect(status().isForbidden());
	}
}
