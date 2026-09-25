package com.mystaria.phantasmon_backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads a project-root {@code .env} into the Spring Environment so local
 * {@code BDD_*} keys work with {@code ./gradlew bootRun} without exporting them
 * in the shell. Missing file is ignored (CI / Testcontainers).
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so other {@link EnvironmentPostProcessor}s
 * (e.g. {@link com.mystaria.phantasmon_backend.logging.SessionLogFileEnvironmentPostProcessor})
 * can rely on {@code .env} values already being present in the {@link ConfigurableEnvironment}.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public int getOrder() {
		return HIGHEST_PRECEDENCE;
	}

	private static final String PROPERTY_SOURCE_NAME = "phantasmonDotenv";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Path envFile = Path.of(".env");
		if (!Files.isRegularFile(envFile)) {
			return;
		}

		Map<String, Object> values = new LinkedHashMap<>();
		try {
			for (String rawLine : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
				String line = rawLine.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				int eq = line.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String key = line.substring(0, eq).trim();
				String value = stripQuotes(line.substring(eq + 1).trim());
				values.put(key, value);
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to read .env", ex);
		}

		if (!values.isEmpty()) {
			environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
		}
	}

	private static String stripQuotes(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}
}
