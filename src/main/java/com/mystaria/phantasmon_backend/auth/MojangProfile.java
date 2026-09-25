package com.mystaria.phantasmon_backend.auth;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mojang {@code hasJoined} response body. {@code id} comes back as a
 * dashless 32-hex-char UUID string, hence {@link #uuid()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MojangProfile(String id, String name) {

	public UUID uuid() {
		String dashed = id.replaceFirst(
				"(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
				"$1-$2-$3-$4-$5");
		return UUID.fromString(dashed);
	}
}
