package com.mystaria.phantasmon_backend.addon;

import java.util.UUID;

/**
 * What a tournament (an add-on's, TODO-35) learns of its matches' games, and how it binds two paired players' lobby
 * to their match. Registered with {@code LiveBattleService.setTournamentHook}; called under that service's lock.
 */
public interface TournamentHook {

	/** A game ended; {@code winnerUuid} null for a draw (disconnection, backend stopping, admin stop). */
	void onGameEnded(UUID matchUuid, UUID winnerUuid, String reason);

	/** The match's lobby closed before its battle started (a player left, the timer ran out...). */
	void onLobbyCancelled(UUID matchUuid, String reason);

	/** The opponent a player is paired with in a running tournament's round, not played yet; null if none. */
	UUID pairedOpponent(UUID playerUuid);

	/** A lobby of two paired players opened: their match (now being played) and its format; null if not paired. */
	Binding bindLobby(UUID playerA, UUID playerB);

	record Binding(UUID matchUuid, String formatId) {
	}
}
