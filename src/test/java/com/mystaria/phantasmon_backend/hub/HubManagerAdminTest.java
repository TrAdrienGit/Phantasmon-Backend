package com.mystaria.phantasmon_backend.hub;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.auth.JwtService;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admins' hub manager (2026-11-10): hubs with creation, anchor count; a hub's anchors; renaming. */
@Import(TestcontainersConfiguration.class)
// Same properties as AdminControllerTest and HubAnchorControllerTest: one shared Spring context.
@SpringBootTest(properties = { "phantasmon.logging.enabled=false", "phantasmon.admin.file=build/test-admins.txt" })
@AutoConfigureMockMvc
class HubManagerAdminTest {

	private static final Path ADMIN_FILE = Path.of("build/test-admins.txt");
	private static final Path SCHEMATICS = Path.of("src/test/resources/hub_schematics");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private PlayerService playerService;

	private String adminName;
	private String adminToken;
	private String playerToken;
	private String hub;
	private String renamed;

	@BeforeEach
	void setUp() throws Exception {
		UUID admin = UUID.randomUUID();
		adminName = "Op" + admin.toString().substring(0, 8);
		playerService.recordConnection(admin, adminName);
		adminToken = "Bearer " + jwtService.issueAccessToken(admin, adminName);
		UUID player = UUID.randomUUID();
		String playerName = "Pl" + player.toString().substring(0, 8);
		playerService.recordConnection(player, playerName);
		playerToken = "Bearer " + jwtService.issueAccessToken(player, playerName);
		Files.createDirectories(ADMIN_FILE.getParent());
		Files.writeString(ADMIN_FILE, adminName + "\n");
		Files.setLastModifiedTime(ADMIN_FILE, FileTime.from(Instant.now().plusSeconds((long) (Math.random() * 1000) + 1)));
		hub = "t_" + UUID.randomUUID().toString().substring(0, 8);
		renamed = hub + "_r";
	}

	@AfterEach
	void cleanUp() throws Exception {
		mockMvc.perform(delete("/admin/hubs/" + hub).header("Authorization", adminToken));
		mockMvc.perform(delete("/admin/hubs/" + renamed).header("Authorization", adminToken));
		try (var folders = Files.list(SCHEMATICS)) {
			for (Path folder : folders.filter(path -> path.getFileName().toString().startsWith("hub_" + hub)).toList()) {
				try (var files = Files.walk(folder)) {
					for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
						Files.delete(path);
					}
				}
			}
		}
	}

	@Test
	void adminsListHubsTheirAnchorsAndRenameThem() throws Exception {
		mockMvc.perform(post("/admin/hubs").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"length\":9,\"width\":9,\"height\":9}".formatted(hub)))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/hub/anchors").header("Authorization", playerToken).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"request_uuid":"%s","hub":"%s","name":"chez moi","server_fingerprint":"srv-%s",
								 "dimension":"minecraft:overworld","origin":{"x":10.5,"y":64.0,"z":-3.5},"yaw":0}
								""".formatted(UUID.randomUUID(), hub, UUID.randomUUID())))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/admin/hubs").header("Authorization", playerToken)).andExpect(status().isForbidden());
		mockMvc.perform(get("/admin/hubs").header("Authorization", adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name == '%s')].anchors".formatted(hub)).value(1))
				.andExpect(jsonPath("$[?(@.name == '%s')].created_by_name".formatted(hub)).value(adminName))
				.andExpect(jsonPath("$[?(@.name == '%s')].available".formatted(hub)).value(true))
				.andExpect(jsonPath("$[?(@.name == '%s')].kind".formatted(hub)).value("NORMAL"))
				.andExpect(jsonPath("$[?(@.name == '%s')].online".formatted(hub)).value(0))
				.andExpect(jsonPath("$[?(@.name == '%s')].created_at".formatted(hub)).isNotEmpty());
		mockMvc.perform(get("/hubs/overview").header("Authorization", playerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name == '%s')].anchors".formatted(hub)).value(1))
				.andExpect(jsonPath("$[?(@.name == '%s')].created_at".formatted(hub)).isNotEmpty())
				.andExpect(jsonPath("$[?(@.name == '%s')].created_by_name".formatted(hub)).value(org.hamcrest.Matchers.contains((Object) null)));
		mockMvc.perform(get("/admin/hubs/" + hub + "/anchors").header("Authorization", adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("chez moi"))
				.andExpect(jsonPath("$[0].owner_name").isNotEmpty())
				.andExpect(jsonPath("$[0].x").value(10.5));

		mockMvc.perform(post("/admin/hubs/" + hub + "/rename").header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bad Name!\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_INVALID_NAME"));
		mockMvc.perform(post("/admin/hubs/" + hub + "/rename").header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"global\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_NAME_TAKEN"));
		mockMvc.perform(post("/admin/hubs/" + hub + "/rename").header("Authorization", adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(renamed)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(renamed));
		assertThat(SCHEMATICS.resolve("hub_" + renamed)).isDirectory();
		assertThat(SCHEMATICS.resolve("hub_" + hub)).doesNotExist();
		mockMvc.perform(get("/admin/hubs/" + renamed + "/anchors").header("Authorization", adminToken))
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void adminsGiveAHubItsSchematicAndTakeItBack() throws Exception {
		mockMvc.perform(post("/admin/hubs").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"%s\",\"length\":21,\"width\":21,\"height\":21}".formatted(hub)))
				.andExpect(status().isCreated());
		byte[] arena = Files.readAllBytes(Path.of("src/test/resources/schematics/arena.litematic"));
		byte[] room = Files.readAllBytes(Path.of("src/test/resources/schematics/room_v3.schem"));

		mockMvc.perform(put("/admin/hubs/" + hub + "/schematic").param("file_name", "arena.litematic")
						.header("Authorization", playerToken).contentType(MediaType.APPLICATION_OCTET_STREAM).content(arena))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/admin/hubs/" + hub + "/schematic").param("file_name", "../evil.txt")
						.header("Authorization", adminToken).contentType(MediaType.APPLICATION_OCTET_STREAM).content(arena))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_SCHEMATIC_NAME"));
		mockMvc.perform(put("/admin/hubs/" + hub + "/schematic").param("file_name", "room.schem")
						.header("Authorization", adminToken).contentType(MediaType.APPLICATION_OCTET_STREAM).content(room))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error_code").value("ERROR_HUB_SCHEMATIC_INVALID"));

		mockMvc.perform(put("/admin/hubs/" + hub + "/schematic").param("file_name", "My Arena.litematic")
						.header("Authorization", adminToken).contentType(MediaType.APPLICATION_OCTET_STREAM).content(arena))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.schematic.name").value("My Arena.litematic"));
		assertThat(SCHEMATICS.resolve("hub_" + hub).resolve("My Arena.litematic")).isRegularFile();
		// A second upload replaces it; the first one is kept aside.
		mockMvc.perform(put("/admin/hubs/" + hub + "/schematic").param("file_name", "arena.litematic")
						.header("Authorization", adminToken).contentType(MediaType.APPLICATION_OCTET_STREAM).content(arena))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.schematic.name").value("arena.litematic"));
		assertThat(SCHEMATICS.resolve("hub_" + hub).resolve("My Arena.litematic")).doesNotExist();
		try (var replaced = Files.list(SCHEMATICS.resolve("hub_" + hub).resolve("replaced"))) {
			assertThat(replaced.count()).isEqualTo(1);
		}

		mockMvc.perform(delete("/admin/hubs/" + hub + "/schematic").header("Authorization", adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.schematic").doesNotExist());
		mockMvc.perform(get("/hubs/" + hub + "/schematic/file").header("Authorization", playerToken))
				.andExpect(status().isNotFound());
	}
}
