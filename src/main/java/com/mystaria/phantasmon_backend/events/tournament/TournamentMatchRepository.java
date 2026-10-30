package com.mystaria.phantasmon_backend.events.tournament;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentMatchRepository extends JpaRepository<TournamentMatch, UUID> {

	List<TournamentMatch> findByTournamentUuidOrderByRoundAscPositionAsc(UUID tournamentUuid);

	/** The player's match of the current round of a running tournament, not decided yet (byes excluded). */
	@org.springframework.data.jpa.repository.Query("select m from TournamentMatch m, Tournament t where t.uuid = m.tournamentUuid"
			+ " and t.status = 'RUNNING' and m.round = t.currentRound and m.status <> 'DONE' and m.player2 is not null"
			+ " and (m.player1 = ?1 or m.player2 = ?1)")
	List<TournamentMatch> findOpenFor(UUID playerUuid);
}
