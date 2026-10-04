package com.mystaria.phantasmon_backend.presence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
		presences.put(playerUuid, new PlayerPresence(playerUuid, serverFingerprint, dimension, null, clock.instant(), null));
	}

	/** Returns the removed presence (if any) so the caller can broadcast a despawn for its active ghost, if it had one. */
	public Optional<PlayerPresence> leave(UUID playerUuid) {
		return Optional.ofNullable(presences.remove(playerUuid));
	}

	public void heartbeat(UUID playerUuid) {
		presences.computeIfPresent(playerUuid, (uuid, presence) -> new PlayerPresence(
				presence.playerUuid(), presence.serverFingerprint(), presence.dimension(),
				presence.position(), clock.instant(), presence.activeGhostPokemonUuid()));
	}

	public void updatePosition(UUID playerUuid, double x, double y, double z, String dimension) {
		presences.computeIfPresent(playerUuid, (uuid, presence) -> new PlayerPresence(
				presence.playerUuid(), presence.serverFingerprint(), dimension,
				new Position(x, y, z), presence.lastHeartbeatAt(), presence.activeGhostPokemonUuid()));
	}

	/** Marks {@code pokemonUuid} as the Ghost Pokémon this player currently has sent out (CAD Partie 2 §7/§8). */
	public void sendOutGhost(UUID playerUuid, UUID pokemonUuid) {
		presences.computeIfPresent(playerUuid, (uuid, presence) -> new PlayerPresence(
				presence.playerUuid(), presence.serverFingerprint(), presence.dimension(),
				presence.position(), presence.lastHeartbeatAt(), pokemonUuid));
	}

	/** Clears whatever Ghost Pokémon this player currently has out, if any. */
	public void recallGhost(UUID playerUuid) {
		presences.computeIfPresent(playerUuid, (uuid, presence) -> new PlayerPresence(
				presence.playerUuid(), presence.serverFingerprint(), presence.dimension(),
				presence.position(), presence.lastHeartbeatAt(), null));
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
			// Null-safe in defence: the handler already refuses missing keys (SEC-4).
			if (!presence.playerUuid().equals(playerUuid)
					&& Objects.equals(presence.serverFingerprint(), self.serverFingerprint())
					&& Objects.equals(presence.dimension(), self.dimension())) {
				members.add(presence.playerUuid());
			}
		}
		return members;
	}

	/**
	 * UUIDs of every presence whose heartbeat is older than the TTL. Does <b>not</b> remove them: the caller leaves
	 * through the normal path so the expired player's Ghost is despawned for its group (removing here first used to
	 * leave nothing for that path to find — BUG-4).
	 */
	public List<UUID> findExpired() {
		Instant threshold = clock.instant().minus(ttl);
		List<UUID> expired = new ArrayList<>();
		for (PlayerPresence presence : presences.values()) {
			if (presence.lastHeartbeatAt().isBefore(threshold)) {
				expired.add(presence.playerUuid());
			}
		}
		return expired;
	}
}
