package com.mystaria.phantasmon_backend.presence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-memory presence registry: groups connected players by
 * {@code server_fingerprint + dimension} (CAD Partie 2 §4) so spawn/move/
 * despawn events can later be relayed only within the same group. No
 * persistence, no multi-instance support (CAD Partie 3 §H) — a single
 * {@link ConcurrentHashMap} local to this backend instance.
 */
@Service
public class PresenceService {

	private final Map<UUID, PlayerPresence> presences = new ConcurrentHashMap<>();
	private final Clock clock;
	private final Duration ttl;

	public PresenceService(Clock clock, @Value("${phantasmon.presence.ttl:PT30S}") Duration ttl) {
		this.clock = clock;
		this.ttl = ttl;
	}

	public void join(UUID playerUuid, String serverFingerprint, String dimension) {
		presences.put(playerUuid, new PlayerPresence(playerUuid, serverFingerprint, dimension, null, clock.instant()));
	}

	public void leave(UUID playerUuid) {
		presences.remove(playerUuid);
	}

	public void heartbeat(UUID playerUuid) {
		presences.computeIfPresent(playerUuid, (uuid, presence) -> new PlayerPresence(
				presence.playerUuid(), presence.serverFingerprint(), presence.dimension(),
				presence.position(), clock.instant()));
	}

	public void updatePosition(UUID playerUuid, double x, double y, double z, String dimension) {
		presences.computeIfPresent(playerUuid, (uuid, presence) -> new PlayerPresence(
				presence.playerUuid(), presence.serverFingerprint(), dimension,
				new Position(x, y, z), presence.lastHeartbeatAt()));
	}

	public Optional<PlayerPresence> find(UUID playerUuid) {
		return Optional.ofNullable(presences.get(playerUuid));
	}

	/** Other players sharing the same {@code server_fingerprint + dimension}, excluding {@code playerUuid} itself. */
	public List<UUID> groupMembers(UUID playerUuid) {
		PlayerPresence self = presences.get(playerUuid);
		if (self == null) {
			return List.of();
		}
		List<UUID> members = new ArrayList<>();
		for (PlayerPresence presence : presences.values()) {
			if (!presence.playerUuid().equals(playerUuid)
					&& presence.serverFingerprint().equals(self.serverFingerprint())
					&& presence.dimension().equals(self.dimension())) {
				members.add(presence.playerUuid());
			}
		}
		return members;
	}

	/** Removes and returns the UUIDs of every presence whose heartbeat is older than the TTL. */
	public List<UUID> cleanupExpired() {
		Instant threshold = clock.instant().minus(ttl);
		List<UUID> expired = new ArrayList<>();
		for (PlayerPresence presence : presences.values()) {
			if (presence.lastHeartbeatAt().isBefore(threshold)) {
				expired.add(presence.playerUuid());
			}
		}
		expired.forEach(presences::remove);
		return expired;
	}
}
