package com.mystaria.phantasmon_backend.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the {@code log/} directory (one file per backend session, see
 * {@code logback-spring.xml}) under a total size cap: when it grows past the
 * cap, the oldest session files are deleted first. Runs once at startup and
 * then periodically, so a single very long session is also covered.
 *
 * <p>Disabled entirely alongside file logging via {@code LOGGING_ENABLED}/
 * {@code phantasmon.logging.enabled} — no point pruning a directory nothing
 * is writing to.
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "phantasmon.logging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogRetentionService {

	private static final Pattern LOG_FILE_NAME = Pattern.compile("Log-Phantasmon-Backend_.*\\.txt");
	private static final long CHECK_INTERVAL_MILLIS = 60 * 60 * 1000L; // hourly

	private final Path logDirectory;
	private final long maxTotalBytes;

	public LogRetentionService(
			@Value("${phantasmon.logging.directory:log}") String logDirectory,
			@Value("${phantasmon.logging.max-total-bytes:5368709120}") long maxTotalBytes) {
		this.logDirectory = Path.of(logDirectory);
		this.maxTotalBytes = maxTotalBytes;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		enforceRetention();
	}

	@Scheduled(fixedRate = CHECK_INTERVAL_MILLIS)
	public void scheduledCheck() {
		enforceRetention();
	}

	void enforceRetention() {
		if (!Files.isDirectory(logDirectory)) {
			return;
		}

		try {
			List<Path> files = new ArrayList<>();
			try (var stream = Files.list(logDirectory)) {
				stream.filter(Files::isRegularFile)
						.filter(path -> LOG_FILE_NAME.matcher(path.getFileName().toString()).matches())
						.forEach(files::add);
			}
			files.sort(Comparator.comparing(this::lastModifiedSafe));

			long totalSize = 0;
			for (Path file : files) {
				totalSize += Files.size(file);
			}

			int index = 0;
			while (totalSize > maxTotalBytes && index < files.size()) {
				Path oldest = files.get(index++);
				long size = Files.size(oldest);
				Files.delete(oldest);
				totalSize -= size;
				log.warn("Log retention: deleted {} ({} bytes) to stay under the {} byte cap",
						oldest.getFileName(), size, maxTotalBytes);
			}
		} catch (IOException ex) {
			log.error("Log retention check failed", ex);
		}
	}

	private FileTime lastModifiedSafe(Path path) {
		try {
			return Files.getLastModifiedTime(path);
		} catch (IOException ex) {
			return FileTime.fromMillis(0);
		}
	}
}
