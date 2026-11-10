package com.mystaria.phantasmon_backend.version;

/** Matches the OpenAPI {@code GET /version} response schema. */
/** {@code addons}: the backend add-ons loaded (D-41), id → version; empty for Phantasmon alone. */
public record VersionResponse(String currentVersion, String minSupportedVersion, java.util.Map<String, String> addons) {
}
