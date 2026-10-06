package com.mystaria.phantasmon_backend.battle.format;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Where the format rules come from (TODO-24, Adrien 2026-10-06: checked for updates at every backend start). At
 * startup each Showdown file is downloaded (formats, tiers, Pokédex, items, moves, abilities from
 * {@code play.pokemonshowdown.com/data/}, rule definitions from the Showdown repository); a file that changed is
 * written over its cached copy ({@code phantasmon.showdown.cache-dir}) and logged. A file that can't be downloaded
 * comes from the cache, else from the snapshot shipped with the backend ({@code resources/showdown/}), so the
 * backend always starts. Tests turn the download off.
 */
@Component
@Slf4j
public class ShowdownDataSource {

	private static final Map<String, String> URLS = Map.of(
			"formats.js", "https://play.pokemonshowdown.com/data/formats.js",
			"formats-data.js", "https://play.pokemonshowdown.com/data/formats-data.js",
			"pokedex.js", "https://play.pokemonshowdown.com/data/pokedex.js",
			"items.js", "https://play.pokemonshowdown.com/data/items.js",
			"moves.js", "https://play.pokemonshowdown.com/data/moves.js",
			"abilities.js", "https://play.pokemonshowdown.com/data/abilities.js",
			"rulesets.ts", "https://raw.githubusercontent.com/smogon/pokemon-showdown/master/data/rulesets.ts");

	private final Path cacheDir;
	private final boolean updateOnStart;
	private volatile ShowdownData data;
	private volatile BattleFormats formats;
	private volatile TeamValidator validator;

	public ShowdownDataSource(@Value("${phantasmon.showdown.cache-dir:showdown-data}") String cacheDir,
			@Value("${phantasmon.showdown.update-on-start:true}") boolean updateOnStart) {
		this.cacheDir = Path.of(cacheDir);
		this.updateOnStart = updateOnStart;
		load();
	}

	public BattleFormats formats() {
		return formats;
	}

	public TeamValidator validator() {
		return validator;
	}

	private void load() {
		Map<String, String> files = new HashMap<>();
		List<String> updated = new ArrayList<>();
		HttpClient http = updateOnStart ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL).build() : null;
		for (String file : ShowdownData.FILES) {
			String cached = readCache(file);
			String fresh = http == null ? null : download(http, file);
			if (fresh != null && !fresh.equals(cached)) {
				writeCache(file, fresh);
				updated.add(file);
			}
			String content = fresh != null ? fresh : cached != null ? cached : bundled(file);
			files.put(file, content);
		}
		try {
			install(ShowdownData.parse(files));
		} catch (RuntimeException ex) {
			// A downloaded file Showdown changed in a way we can't read: fall back to the shipped snapshot.
			log.error("Cannot read the Showdown data; using the snapshot shipped with the backend", ex);
			Map<String, String> snapshot = new HashMap<>();
			ShowdownData.FILES.forEach(file -> snapshot.put(file, bundled(file)));
			install(ShowdownData.parse(snapshot));
		}
		if (!updated.isEmpty()) {
			log.info("Showdown format rules updated at startup: {}", updated);
		} else {
			log.info("Showdown format rules loaded ({}), no update", updateOnStart ? "checked online" : "no online check");
		}
	}

	private void install(ShowdownData parsed) {
		data = parsed;
		formats = new BattleFormats(parsed);
		validator = new TeamValidator(parsed);
		long missing = BattleFormats.OFFERED.stream().filter(id -> formats.get(id) == null).count();
		if (missing > 0) {
			log.warn("{} offered battle format(s) no longer exist in Showdown's data", missing);
		}
	}

	private String download(HttpClient http, String file) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(URLS.get(file))).timeout(Duration.ofSeconds(20)).GET().build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() == 200 && !response.body().isBlank()) {
				return response.body();
			}
			log.warn("Showdown data {}: HTTP {}", file, response.statusCode());
		} catch (IOException ex) {
			log.warn("Showdown data {} not downloaded: {}", file, ex.toString());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		return null;
	}

	private String readCache(String file) {
		Path path = cacheDir.resolve(file);
		try {
			return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
		} catch (IOException ex) {
			log.warn("Cannot read cached {}", path, ex);
			return null;
		}
	}

	private void writeCache(String file, String content) {
		try {
			Files.createDirectories(cacheDir);
			Files.writeString(cacheDir.resolve(file), content, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			log.warn("Cannot cache {}", file, ex);
		}
	}

	/** The snapshot shipped in {@code resources/showdown/}. */
	public static String bundled(String file) {
		try (InputStream stream = ShowdownDataSource.class.getResourceAsStream("/showdown/" + file)) {
			if (stream == null) {
				throw new IllegalStateException("Missing bundled Showdown file " + file);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot read bundled Showdown file " + file, ex);
		}
	}

	/** The shipped snapshot, parsed (tests). */
	public static ShowdownData bundledData() {
		Map<String, String> files = new HashMap<>();
		ShowdownData.FILES.forEach(file -> files.put(file, bundled(file)));
		return ShowdownData.parse(files);
	}
}
