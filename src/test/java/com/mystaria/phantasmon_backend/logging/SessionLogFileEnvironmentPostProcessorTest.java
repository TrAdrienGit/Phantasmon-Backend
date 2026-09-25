package com.mystaria.phantasmon_backend.logging;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLogFileEnvironmentPostProcessorTest {

	private final SessionLogFileEnvironmentPostProcessor processor = new SessionLogFileEnvironmentPostProcessor();

	@Test
	void setsASessionLogFileNameUnderTheConfiguredDirectoryWhenEnabled() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("phantasmon.logging.enabled", "true");
		environment.setProperty("phantasmon.logging.directory", "custom-log-dir");

		processor.postProcessEnvironment(environment, null);

		String fileName = environment.getProperty("logging.file.name");
		assertThat(fileName).isNotNull();
		assertThat(fileName).matches("custom-log-dir/Log-Phantasmon-Backend_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.txt");
	}

	@Test
	void defaultsToTheLogDirectoryWhenNoneConfigured() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("phantasmon.logging.enabled", "true");

		processor.postProcessEnvironment(environment, null);

		assertThat(environment.getProperty("logging.file.name")).startsWith("log/Log-Phantasmon-Backend_");
	}

	@Test
	void setsNoLoggingFileNameWhenDisabled() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("phantasmon.logging.enabled", "false");

		processor.postProcessEnvironment(environment, null);

		assertThat(environment.getProperty("logging.file.name")).isNull();
	}

	@Test
	void defaultsToEnabledWhenPropertyIsAbsent() {
		MockEnvironment environment = new MockEnvironment();

		processor.postProcessEnvironment(environment, null);

		assertThat(environment.getProperty("logging.file.name")).isNotNull();
	}
}
