package com.mystaria.phantasmon_backend.version;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Version handshake (CAD Partie 3 §E): the client calls this before starting
 * the Mojang auth flow so an incompatible build fails fast with a clear
 * message instead of engaging auth needlessly.
 */
@RestController
public class VersionController {

	private final String currentVersion;
	private final String minSupportedVersion;

	public VersionController(
			@Value("${phantasmon.version.current}") String currentVersion,
			@Value("${phantasmon.version.min-supported}") String minSupportedVersion) {
		this.currentVersion = currentVersion;
		this.minSupportedVersion = minSupportedVersion;
	}

	@GetMapping("/version")
	public VersionResponse version() {
		return new VersionResponse(currentVersion, minSupportedVersion);
	}
}
