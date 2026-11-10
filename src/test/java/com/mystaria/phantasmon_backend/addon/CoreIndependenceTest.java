package com.mystaria.phantasmon_backend.addon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * TODO-35: the core never uses Phantasmon Events' classes, only the {@code addon} extension points — so the
 * {@code events} package can leave for its own module without touching the core.
 */
class CoreIndependenceTest {

	private static final Path SOURCES = Path.of("src/main/java/com/mystaria/phantasmon_backend");

	@Test
	void noCoreClassReferencesTheEventsPackage() throws IOException {
		List<String> offenders;
		try (Stream<Path> files = Files.walk(SOURCES)) {
			offenders = files
					.filter(file -> file.toString().endsWith(".java"))
					.filter(file -> !file.startsWith(SOURCES.resolve("events")))
					.filter(file -> {
						try {
							return Files.readString(file).contains("phantasmon_backend.events");
						} catch (IOException ex) {
							throw new IllegalStateException(ex);
						}
					})
					.map(Path::toString)
					.toList();
		}
		assertThat(offenders).isEmpty();
	}
}
