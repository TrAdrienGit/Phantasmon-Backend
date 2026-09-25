package com.mystaria.phantasmon_backend.version;

/** Matches the OpenAPI {@code GET /version} response schema. */
public record VersionResponse(String currentVersion, String minSupportedVersion) {
}
