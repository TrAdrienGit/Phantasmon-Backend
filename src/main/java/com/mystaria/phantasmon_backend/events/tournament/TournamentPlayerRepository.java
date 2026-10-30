package com.mystaria.phantasmon_backend.events.tournament;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentPlayerRepository extends JpaRepository<TournamentPlayer, TournamentPlayer.Key> {

	List<TournamentPlayer> findByKeyTournamentUuidOrderByRegisteredAtAsc(UUID tournamentUuid);
}
