package com.mystaria.phantasmon_backend.version;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.addon.BackendAddon;

/**
 * Version handshake (CAD Partie 3 §E): the client calls this before starting
 * the Mojang auth flow so an incompatible build fails fast with a clear
 * message instead of engaging auth needlessly.
 */
@RestController
public class VersionController {

	private final String currentVersion;
	private final String minSupportedVersion;
	private final ObjectProvider<BackendAddon> addons;

	public VersionController(
			@Value("${phantasmon.version.current}") String currentVersion,
			@Value("${phantasmon.version.min-supported}") String minSupportedVersion,
			ObjectProvider<BackendAddon> addons) {
		this.currentVersion = currentVersion;
		this.minSupportedVersion = minSupportedVersion;
		this.addons = addons;
	}

	@GetMapping("/version")
	public VersionResponse version() {
		Map<String, String> loaded = new TreeMap<>();
		addons.orderedStream().forEach(addon -> loaded.put(addon.id(), addon.version()));
		return new VersionResponse(currentVersion, minSupportedVersion, loaded);
	}
}
