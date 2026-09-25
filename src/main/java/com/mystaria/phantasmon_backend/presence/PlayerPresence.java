package com.mystaria.phantasmon_backend.presence;

import java.time.Instant;
import java.util.UUID;

/**
 * Ephemeral, in-memory-only record of a connected Ghost Client (CAD Partie 2
 * §4, Partie 3 §F, PHANTASMON_DB_SCHEMA.md §8 — deliberately **not** a SQL
 * table). Immutable; {@link PresenceService} replaces the map entry wholesale
 * on each update rather than mutating shared state.
 */
public record PlayerPresence(
		UUID playerUuid,
		String serverFingerprint,
		String dimension,
		Position position,
		Instant lastHeartbeatAt) {
}
