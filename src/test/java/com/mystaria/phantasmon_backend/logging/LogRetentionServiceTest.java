package com.mystaria.phantasmon_backend.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LogRetentionServiceTest {

	@TempDir
	Path logDirectory;

	@Test
	void deletesOldestFilesFirstWhenOverCap() throws IOException {
		Path oldest = writeFile("Log-Phantasmon-Backend_2026-01-01_00-00-00.txt", 100, Instant.now().minus(3, ChronoUnit.DAYS));
		Path middle = writeFile("Log-Phantasmon-Backend_2026-01-02_00-00-00.txt", 100, Instant.now().minus(2, ChronoUnit.DAYS));
		Path newest = writeFile("Log-Phantasmon-Backend_2026-01-03_00-00-00.txt", 100, Instant.now().minus(1, ChronoUnit.DAYS));

		LogRetentionService service = new LogRetentionService(logDirectory.toString(), 250);
		service.enforceRetention();

		assertThat(Files.exists(oldest)).isFalse();
		assertThat(Files.exists(middle)).isTrue();
		assertThat(Files.exists(newest)).isTrue();
	}

	@Test
	void deletesNothingWhenUnderCap() throws IOException {
		Path fileA = writeFile("Log-Phantasmon-Backend_2026-01-01_00-00-00.txt", 100, Instant.now());
		Path fileB = writeFile("Log-Phantasmon-Backend_2026-01-02_00-00-00.txt", 100, Instant.now());

		LogRetentionService service = new LogRetentionService(logDirectory.toString(), 10_000);
		service.enforceRetention();

		assertThat(Files.exists(fileA)).isTrue();
		assertThat(Files.exists(fileB)).isTrue();
	}

	@Test
	void ignoresFilesNotMatchingTheLogNamingPattern() throws IOException {
		Path unrelated = writeFile("some-other-file.txt", 100, Instant.now().minus(5, ChronoUnit.DAYS));
		Path logFile = writeFile("Log-Phantasmon-Backend_2026-01-01_00-00-00.txt", 100, Instant.now());

		LogRetentionService service = new LogRetentionService(logDirectory.toString(), 100);
		service.enforceRetention();

		assertThat(Files.exists(unrelated)).isTrue();
		assertThat(Files.exists(logFile)).isTrue();
	}

	@Test
	void doesNothingWhenDirectoryDoesNotExist() {
		LogRetentionService service = new LogRetentionService(logDirectory.resolve("missing").toString(), 1);

		assertThatCode(() -> service.enforceRetention()).doesNotThrowAnyException();
	}

	private Path writeFile(String name, int sizeBytes, Instant lastModified) throws IOException {
		Path file = logDirectory.resolve(name);
		Files.write(file, new byte[sizeBytes]);
		Files.setLastModifiedTime(file, FileTime.from(lastModified));
		return file;
	}
}
