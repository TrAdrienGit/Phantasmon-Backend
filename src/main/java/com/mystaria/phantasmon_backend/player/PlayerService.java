package com.mystaria.phantasmon_backend.player;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Player identity lifecycle: created on first successful connection, refreshed
 * on every subsequent one (CAD Partie 4 Phase 1, PHANTASMON_DB_SCHEMA.md §3).
 */
@Service
@Slf4j
public class PlayerService {

	private final PlayerRepository playerRepository;

	public PlayerService(PlayerRepository playerRepository) {
		this.playerRepository = playerRepository;
	}

	@Transactional
	public Player recordConnection(UUID uuid, String username) {
		Instant now = Instant.now();
		return playerRepository.findById(uuid)
				.map(player -> {
					player.setLastUsername(username);
					player.setLastSeenAt(now);
					log.debug("Player {} ({}) last_seen_at refreshed", username, uuid);
					return player;
				})
				.orElseGet(() -> {
					log.info("New player created: {} ({})", username, uuid);
					return playerRepository.save(new Player(uuid, username, now, now));
				});
	}

	public Optional<Player> findById(UUID uuid) {
		return playerRepository.findById(uuid);
	}
}
