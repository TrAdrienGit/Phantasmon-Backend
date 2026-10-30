package com.mystaria.phantasmon_backend.events.tournament;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A double elimination bracket (plan-tournois.md, phase T4), pure logic: its shape, where winners and losers go, the
 * waves an admin opens one after the other, and the placements.
 *
 * <p>{@code N} = the power of two at least the number of players, {@code k} = log₂ N. Winners bracket: rounds 1..k.
 * Losers bracket: rounds 1..2(k-1) — odd rounds play the losers bracket's survivors among themselves (round 1: the
 * winners bracket's round 1 losers), even rounds face them with the winners bracket's next losers (in reverse order,
 * to avoid rematches). Grand final: the winners bracket's champion against the losers bracket's; if the latter wins,
 * a reset final (Adrien: the one from the losers bracket must win twice).
 *
 * <p>Slots: {@code null} not known yet, {@link #NOBODY} known to stay empty (a bye): a match against nobody is a
 * walkover.
 */
public final class DoubleElimination {

	public static final String WINNERS = "WINNERS";
	public static final String LOSERS = "LOSERS";
	public static final String FINAL = "FINAL";
	public static final String RESET = "RESET";
	/** A slot that will stay empty (bye, or the loser of a walkover). */
	public static final UUID NOBODY = new UUID(0, 0);

	/** A match's place: bracket, round, position; {@code wave}: when an admin opens it. */
	public record Node(String bracket, int round, int position, int wave) {
	}

	/** Where a player goes: a match and its slot (0 or 1); null: out of the tournament. */
	public record Destination(String bracket, int round, int position, int slot) {
	}

	private DoubleElimination() {
	}

	/** {@code k}: log₂ of the bracket size (at least 1). */
	public static int levels(int players) {
		int k = 1;
		while ((1 << k) < players) {
			k++;
		}
		return k;
	}

	/** Every match of the bracket, the reset final included. */
	public static List<Node> shape(int players) {
		int k = levels(players);
		int n = 1 << k;
		List<Node> nodes = new ArrayList<>();
		for (int r = 1; r <= k; r++) {
			for (int p = 0; p < n >> r; p++) {
				nodes.add(new Node(WINNERS, r, p, r == 1 ? 1 : 2 * r - 2));
			}
		}
		for (int l = 1; l <= 2 * (k - 1); l++) {
			for (int p = 0; p < losersMatches(n, l); p++) {
				nodes.add(new Node(LOSERS, l, p, l + 1));
			}
		}
		nodes.add(new Node(FINAL, 1, 0, finalWave(k)));
		nodes.add(new Node(RESET, 1, 0, finalWave(k) + 1));
		return nodes;
	}

	static int losersMatches(int n, int round) {
		int j = (round + 1) / 2;
		return n >> (j + 1);
	}

	static int finalWave(int k) {
		return k == 1 ? 2 : 2 * k;
	}

	/** The last wave (the reset final's). */
	public static int waves(int players) {
		return finalWave(levels(players)) + 1;
	}

	/** Round 1 of the winners bracket: players in slot 0 of every match first, then slot 1 (never two byes). */
	public static Map<Integer, UUID[]> seed(List<UUID> players) {
		int n = 1 << levels(players.size());
		Map<Integer, UUID[]> matches = new HashMap<>();
		for (int p = 0; p < n / 2; p++) {
			matches.put(p, new UUID[] { NOBODY, NOBODY });
		}
		for (int i = 0; i < players.size(); i++) {
			matches.get(i % (n / 2))[i / (n / 2)] = players.get(i);
		}
		return matches;
	}

	/** Where the winner of that match goes; null after the grand final (the reset is decided by the caller). */
	public static Destination winnerGoes(int players, String bracket, int round, int position) {
		int k = levels(players);
		return switch (bracket) {
			case WINNERS -> round < k ? new Destination(WINNERS, round + 1, position / 2, position % 2)
					: new Destination(FINAL, 1, 0, 0);
			case LOSERS -> round == 2 * (k - 1) ? new Destination(FINAL, 1, 0, 1)
					: round % 2 == 1 ? new Destination(LOSERS, round + 1, position, 0)
							: new Destination(LOSERS, round + 1, position / 2, position % 2);
			default -> null;
		};
	}

	/** Where the loser of that match goes; null: out (losers bracket, finals). */
	public static Destination loserGoes(int players, String bracket, int round, int position) {
		int k = levels(players);
		int n = 1 << k;
		if (!WINNERS.equals(bracket)) {
			return null;
		}
		if (k == 1) {
			return new Destination(FINAL, 1, 0, 1); // two players: the loser is the losers bracket's champion
		}
		if (round == 1) {
			return new Destination(LOSERS, 1, position / 2, position % 2);
		}
		int count = n >> round;
		return new Destination(LOSERS, 2 * (round - 1), count - 1 - position, 1);
	}
}
