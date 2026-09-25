package com.mystaria.phantasmon_backend.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Computes a fresh {@code logging.file.name} once per JVM start — one log
 * file per backend session, named {@code Log-Phantasmon-Backend_<date>_<time>.txt}
 * under {@code phantasmon.logging.directory} (default {@code log/}) — and only
 * if file logging is enabled ({@code LOGGING_ENABLED}/{@code phantasmon.logging.enabled},
 * default true).
 *
 * <p>Deliberately reuses Spring Boot's own {@code logging.file.name} property
 * rather than a custom {@code logback-spring.xml}: Boot's built-in logging
 * bootstrap only attaches a file appender at all when this property is set,
 * which gives the on/off toggle "for free" without fighting Logback's
 * Janino/{@code <springProperty>} two-pass initialization order (a plain
 * {@code <if>} in a custom XML config cannot reliably see Spring-resolved
 * properties this early — see project memory for the failed attempt).
 *
 * <p>Runs after {@link com.mystaria.phantasmon_backend.config.DotenvEnvironmentPostProcessor}
 * so {@code .env}'s {@code LOGGING_ENABLED} is already visible here.
 */
public class SessionLogFileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final String PROPERTY_SOURCE_NAME = "phantasmonSessionLogFile";
	private static final DateTimeFormatter SESSION_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	@Override
	public int getOrder() {
		return LOWEST_PRECEDENCE;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		boolean enabled = environment.getProperty("phantasmon.logging.enabled", Boolean.class,
				environment.getProperty("LOGGING_ENABLED", Boolean.class, true));
		if (!enabled) {
			return;
		}

		String directory = environment.getProperty("phantasmon.logging.directory", "log");
		String fileName = directory + "/Log-Phantasmon-Backend_" + SESSION_TIMESTAMP.format(LocalDateTime.now()) + ".txt";

		environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
				"logging.file.name", fileName,
				// One plain file per session, no automatic mid-session rotation: the
				// 5 GiB folder-wide cap and oldest-file pruning is handled ourselves
				// by LogRetentionService, not Logback's own rolling policy.
				"logging.logback.rollingpolicy.max-file-size", "4GB",
				"logging.logback.rollingpolicy.total-size-cap", "0")));
	}
}
