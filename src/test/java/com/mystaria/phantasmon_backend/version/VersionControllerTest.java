package com.mystaria.phantasmon_backend.version;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@AutoConfigureMockMvc
class VersionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void versionIsPubliclyAccessibleAndReturnsBothVersions() throws Exception {
		mockMvc.perform(get("/version"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current_version").isNotEmpty())
				.andExpect(jsonPath("$.min_supported_version").isNotEmpty());
	}
}
