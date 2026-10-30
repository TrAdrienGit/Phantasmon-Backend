package com.mystaria.phantasmon_backend.events.tournament;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TournamentMatchRepository extends JpaRepository<TournamentMatch, UUID> {

	List<TournamentMatch> findByTournamentUuidOrderByRoundAscPositionAsc(UUID tournamentUuid);

	/**
	 * The player's playable match in a running tournament, not decided yet: Swiss, of the current round (byes excluded);
	 * bracket, of a wave already opened and with both players known ({@code nobody}: {@link DoubleElimination#NOBODY}).
	 */
	@org.springframework.data.jpa.repository.Query("select m from TournamentMatch m, Tournament t where t.uuid = m.tournamentUuid"
			+ " and t.status = 'RUNNING' and m.status <> 'DONE' and m.player1 is not null and m.player2 is not null"
			+ " and m.player1 <> ?2 and m.player2 <> ?2"
			+ " and ((t.kind = 'SWISS' and m.round = t.currentRound) or (t.kind = 'BRACKET' and m.wave <= t.currentRound))"
			+ " and (m.player1 = ?1 or m.player2 = ?1)")
	List<TournamentMatch> findOpenFor(UUID playerUuid, UUID nobody);

	default List<TournamentMatch> findOpenFor(UUID playerUuid) {
		return findOpenFor(playerUuid, DoubleElimination.NOBODY);
	}
}
