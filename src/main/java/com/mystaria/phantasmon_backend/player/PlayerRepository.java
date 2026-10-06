package com.mystaria.phantasmon_backend.player;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, UUID> {

	/** Admin lookups by name (TODO-25): the most recently seen player who last logged in under that name. */
	Optional<Player> findFirstByLastUsernameIgnoreCaseOrderByLastSeenAtDesc(String lastUsername);
}
