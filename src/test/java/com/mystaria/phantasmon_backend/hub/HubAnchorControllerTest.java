package com.mystaria.phantasmon_backend.hub;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

	/** Each new anchor of a test stands 100 blocks east of the previous one, so they never overlap (D-35). */
	private int anchorsPosed;

	private String body(UUID requestUuid, String hub, String name, String server, String dimension, double yaw) {
		return """
				{"request_uuid":"%s","hub":"%s","name":"%s","server_fingerprint":"%s","dimension":"%s",
				 "origin":{"x":%s,"y":64.0,"z":-33.5},"yaw":%s}
				""".formatted(requestUuid, hub, name, server, dimension, 120.5 + 100 * anchorsPosed++, yaw);
	}

	private ResultActions create(String token, String name, String server, double yaw) throws Exception {
		return create(token, UUID.randomUUID(), name, server, "minecraft:overworld", yaw);
	}

	private ResultActions create(String token, UUID requestUuid, String name, String server, String dimension, double yaw)
			throws Exception {
		return createIn(token, requestUuid, "global", name, server, dimension, yaw);
	}

	private ResultActions createIn(String token, UUID requestUuid, String hub, String name, String server, String dimension,
			double yaw) throws Exception {
		return mockMvc.perform(post("/hub/anchors").header("Authorization", token)
				.contentType(MediaType.APPLICATION_JSON).content(body(requestUuid, hub, name, server, dimension, yaw)));
	}

	/** An anchor at exactly these coordinates, in a hub. */
	private ResultActions createAt(String token, String hub, String name, double x, double y, double z, double yaw)
			throws Exception {
		return mockMvc.perform(post("/hub/anchors").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"request_uuid":"%s","hub":"%s","name":"%s","server_fingerprint":"%s","dimension":"minecraft:overworld",
						 "origin":{"x":%s,"y":%s,"z":%s},"yaw":%s}
						""".formatted(UUID.randomUUID(), hub, name, server, x, y, z, yaw)));
	}

	private static final Path SCHEMATICS = Path.of("src/test/resources/hub_schematics");

	/** Hubs created by a test: deleted afterwards, their folders (and archives) removed. */
	@AfterEach
	void removeTestHubs() throws Exception {
		for (String hub : List.copyOf(testHubs)) {
			mockMvc.perform(delete("/admin/hubs/" + hub).header("Authorization", adminToken));
		}
		testHubs.clear();
		try (var folders = Files.list(SCHEMATICS)) {
			for (Path folder : folders.filter(path -> path.getFileName().toString().startsWith("hub_t_")).toList()) {
				try (var files = Files.walk(folder)) {
					for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
						Files.delete(path);
					}
				}
			}
		}
	}

	private final List<String> testHubs = new java.util.ArrayList<>();

	private ResultActions createHub(String token, String name, Object length, Object width, Object height) throws Exception {
		return mockMvc.perform(post("/admin/hubs").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\",\"length\":%s,\"width\":%s,\"height\":%s}".formatted(name, length, width, height)));
	}

	/** A fresh hub of this test, {@code t_<random>}. */
	private String newHub(int length, int width, int height) throws Exception {
		String name = "t_" + UUID.randomUUID().toString().substring(0, 8);
		createHub(adminToken, name, length, width, height).andExpect(status().isCreated());
		testHubs.add(name);
		return name;
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
				.andExpect(jsonPath("$.hub").value("global"))
				.andExpect(jsonPath("$.size.x").value(21))
				.andExpect(jsonPath("$.size.y").value(21))
				.andExpect(jsonPath("$.size.z").value(21))
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
	void aPlayerHasOneAnchorPerHubAtMost() throws Exception {
		String first = uuidOf(create(aliceToken, "Hub", server, 0).andExpect(status().isCreated()));

		create(aliceToken, "Second", "srv-" + UUID.randomUUID(), 0)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_ANCHOR_QUOTA"))
				.andExpect(jsonPath("$.details.anchor_uuid").value(first))
				.andExpect(jsonPath("$.details.hub").value("global"));
		String arena = newHub(15, 11, 9);
		createIn(aliceToken, UUID.randomUUID(), arena, "Second", server, "minecraft:overworld", 0)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.hub").value(arena))
				.andExpect(jsonPath("$.size.x").value(11))
				.andExpect(jsonPath("$.size.y").value(9))
				.andExpect(jsonPath("$.size.z").value(15));
		createIn(aliceToken, UUID.randomUUID(), "nowhere", "Third", server, "minecraft:overworld", 0)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_NOT_FOUND"));
	}

	@Test
	void twoAnchorsNeverOverlapWhateverTheirHub() throws Exception {
		// "global" is 21 wide: x from 90 to 110 around 100.5; a 3x3x3 hub posed at x = 112 starts at 111.
		createAt(aliceToken, "global", "Centre", 100.5, 64.0, 0.5, 0).andExpect(status().isCreated());
		String small = newHub(3, 3, 3);
		createAt(bobToken, small, "Touching", 110.5, 64.0, 0.5, 0)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_ANCHOR_OVERLAP"))
				.andExpect(jsonPath("$.details.name").value("Centre"))
				.andExpect(jsonPath("$.details.hub").value("global"));
		createAt(bobToken, small, "Beside", 112.5, 64.0, 0.5, 0).andExpect(status().isCreated());
		// Above the global hub's 21 blocks of height: free; below: overlaps.
		createAt(adminToken, small, "Above", 100.5, 85.0, 0.5, 0).andExpect(status().isCreated());

		// A long hub turns with its anchor: 3 wide, 15 long, posed facing west it spans x and not z.
		String corridor = newHub(15, 3, 3);
		createAt(tokenFor(UUID.randomUUID(), "Co"), corridor, "Corridor", 100.5, 64.0, 30.5, 90).andExpect(status().isCreated());
		createAt(tokenFor(UUID.randomUUID(), "Dd"), small, "Diagonal", 106.5, 64.0, 30.5, 0)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.details.name").value("Corridor"));
		createAt(tokenFor(UUID.randomUUID(), "Ee"), small, "South", 100.5, 64.0, 35.5, 0).andExpect(status().isCreated());
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
				.andExpect(jsonPath("$.details.fields", containsInAnyOrder("hub", "origin.x", "origin.z", "yaw")));
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
	void aPlayerFindsTheirOwnAnchors() throws Exception {
		mockMvc.perform(get("/hub/anchors/mine").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		create(aliceToken, "Hub", server, 0);

		mockMvc.perform(get("/hub/anchors/mine").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Hub"))
				.andExpect(jsonPath("$[0].hub").value("global"));
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
	void theHubsAreListedWithTheirBuildWhichIsServedWhole() throws Exception {
		byte[] file = Files.readAllBytes(SCHEMATICS.resolve("hub_global/test_hub.schem"));
		mockMvc.perform(get("/hubs").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name == 'global')].size.x").value(21))
				.andExpect(jsonPath("$[?(@.name == 'global')].schematic.name").value("test_hub.schem"))
				.andExpect(jsonPath("$[?(@.name == 'global')].schematic.format").value("SCHEM"))
				.andExpect(jsonPath("$[?(@.name == 'global')].schematic.bytes").value(file.length))
				.andExpect(jsonPath("$[?(@.name == 'global')].schematic.sha256").value(java.util.HexFormat.of().formatHex(
						java.security.MessageDigest.getInstance("SHA-256").digest(file))));
		byte[] served = mockMvc.perform(get("/hubs/GLOBAL/schematic/file").header("Authorization", aliceToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsByteArray();
		org.assertj.core.api.Assertions.assertThat(served).isEqualTo(file);
		mockMvc.perform(get("/hubs")).andExpect(status().isForbidden());
		mockMvc.perform(get("/hubs/nowhere/schematic/file").header("Authorization", aliceToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_NOT_FOUND"));
	}

	@Test
	void anAdminCreatesAHubWithItsSchematicFolder() throws Exception {
		createHub(aliceToken, "t_nope", 9, 9, 9)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error_code").value("ERROR_ADMIN_REQUIRED"));
		createHub(adminToken, "Bad Name!", 9, 9, 9).andExpect(jsonPath("$.error_code").value("ERROR_HUB_INVALID_NAME"));
		createHub(adminToken, "t_size", 2, 9, 9).andExpect(jsonPath("$.error_code").value("ERROR_HUB_INVALID_SIZE"));
		createHub(adminToken, "t_size", 9, 65, 9).andExpect(jsonPath("$.error_code").value("ERROR_HUB_INVALID_SIZE"));
		createHub(adminToken, "t_size", 9, 9, null).andExpect(jsonPath("$.error_code").value("ERROR_HUB_INVALID_SIZE"));
		createHub(adminToken, "GLOBAL", 9, 9, 9)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_NAME_TAKEN"));

		String name = "t_" + UUID.randomUUID().toString().substring(0, 8);
		testHubs.add(name);
		// Upper case is folded: the name is also a folder name.
		createHub(adminToken, name.toUpperCase(), 15, 11, 9)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.size.x").value(11))
				.andExpect(jsonPath("$.size.y").value(9))
				.andExpect(jsonPath("$.size.z").value(15))
				.andExpect(jsonPath("$.schematic").doesNotExist());
		org.assertj.core.api.Assertions.assertThat(SCHEMATICS.resolve("hub_" + name)).isDirectory();
		mockMvc.perform(get("/hubs/" + name + "/schematic/file").header("Authorization", aliceToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_SCHEMATIC_NONE"));
	}

	@Test
	void anAdminReloadsAHubsBuildWhichMustHaveItsSize() throws Exception {
		String hub = newHub(3, 3, 3);
		Path folder = SCHEMATICS.resolve("hub_" + hub);
		// The fixture cube is 3 x 3 x 3: the hub's size.
		Files.copy(Path.of("src/test/resources/schematics/cube.litematic"), folder.resolve("cube.litematic"));
		mockMvc.perform(post("/admin/hubs/" + hub + "/reload").header("Authorization", aliceToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/hubs/" + hub + "/reload").header("Authorization", adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.schematic.name").value("cube.litematic"))
				.andExpect(jsonPath("$.schematic.format").value("LITEMATIC"));

		// Two files, or the wrong size: refused, the previous build stays.
		Files.copy(Path.of("src/test/resources/schematics/arena_v3.schem"), folder.resolve("arena.schem"));
		mockMvc.perform(post("/admin/hubs/" + hub + "/reload").header("Authorization", adminToken))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_SCHEMATIC_INVALID"));
		Files.delete(folder.resolve("cube.litematic"));
		mockMvc.perform(post("/admin/hubs/" + hub + "/reload").header("Authorization", adminToken))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.details.reason").value(org.hamcrest.Matchers.containsString("21x21x21")));
		mockMvc.perform(get("/hubs/" + hub + "/schematic/file").header("Authorization", aliceToken))
				.andExpect(status().isOk());
	}

	@Test
	void deletingAHubDeletesItsAnchorsAndArchivesItsFolder() throws Exception {
		String hub = newHub(5, 5, 5);
		String anchor = uuidOf(createIn(aliceToken, UUID.randomUUID(), hub, "Doomed", server, "minecraft:overworld", 0)
				.andExpect(status().isCreated()));
		mockMvc.perform(delete("/admin/hubs/" + hub).header("Authorization", aliceToken))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete("/admin/hubs/" + hub).header("Authorization", adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hub").value(hub))
				.andExpect(jsonPath("$.anchors_deleted").value(1))
				.andExpect(jsonPath("$.archived_as").value(org.hamcrest.Matchers.startsWith("hub_" + hub + ".deleted-")));
		testHubs.remove(hub);
		org.assertj.core.api.Assertions.assertThat(SCHEMATICS.resolve("hub_" + hub)).doesNotExist();
		mockMvc.perform(delete("/hub/anchors/" + anchor).header("Authorization", aliceToken))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete("/admin/hubs/" + hub).header("Authorization", adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_NOT_FOUND"));
	}
}
