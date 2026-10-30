package com.mystaria.phantasmon_backend.events.tournament;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Swiss rounds (plan-tournois.md §3), pure logic: standings and pairings.
 *
 * <ul>
 * <li>A match won: 1 point, lost: 0; a bye: 1 point.</li>
 * <li>Ranking: points, then Buchholz (the points of the opponents met), then the opponents' win rate, then the
 * head-to-head between two players still tied.</li>
 * <li>Pairing: by standings, each player with the best-ranked one he has not met yet (players with the same points
 * meet first, the last of an odd group goes down); no rematch while it can be avoided. An odd number: the
 * lowest-ranked player who never had a bye sits out.</li>
 * </ul>
 */
public final class Swiss {

	/** A finished match; {@code player2} null for a bye (won by player1). */
	public record Result(UUID player1, UUID player2, UUID winner) {
	}

	public record Row(UUID player, int points, int wins, int losses, int buchholz, double opponentsWinRate) {
	}

	/** Pairs of a round, and who sits out (null if nobody). */
	public record Pairing(List<UUID[]> pairs, UUID bye) {
	}

	private static final int SEARCH_LIMIT = 200_000;

	private Swiss() {
	}

	/** The usual number of rounds: log₂ of the players, rounded up (at least 1). */
	public static int plannedRounds(int players) {
		int rounds = 1;
		while ((1 << rounds) < players) {
			rounds++;
		}
		return rounds;
	}

	/** Standings of {@code players} (in their order when everything is equal) after {@code results}. */
	public static List<Row> standings(List<UUID> players, Collection<Result> results) {
		Map<UUID, Integer> points = new HashMap<>();
		Map<UUID, Integer> wins = new HashMap<>();
		Map<UUID, Integer> losses = new HashMap<>();
		Map<UUID, List<UUID>> opponents = new HashMap<>();
		for (Result result : results) {
			if (result.winner() == null) {
				continue;
			}
			if (result.player2() == null) {
				points.merge(result.player1(), 1, Integer::sum);
				continue;
			}
			UUID loser = result.winner().equals(result.player1()) ? result.player2() : result.player1();
			points.merge(result.winner(), 1, Integer::sum);
			wins.merge(result.winner(), 1, Integer::sum);
			losses.merge(loser, 1, Integer::sum);
			opponents.computeIfAbsent(result.player1(), k -> new ArrayList<>()).add(result.player2());
			opponents.computeIfAbsent(result.player2(), k -> new ArrayList<>()).add(result.player1());
		}
		Map<UUID, Row> rows = new LinkedHashMap<>();
		for (UUID player : players) {
			List<UUID> met = opponents.getOrDefault(player, List.of());
			int buchholz = met.stream().mapToInt(opponent -> points.getOrDefault(opponent, 0)).sum();
			double rate = met.stream().mapToDouble(opponent -> {
				int played = wins.getOrDefault(opponent, 0) + losses.getOrDefault(opponent, 0);
				return played == 0 ? 0 : (double) wins.getOrDefault(opponent, 0) / played;
			}).average().orElse(0);
			rows.put(player, new Row(player, points.getOrDefault(player, 0), wins.getOrDefault(player, 0),
					losses.getOrDefault(player, 0), buchholz, rate));
		}
		List<Row> sorted = new ArrayList<>(rows.values());
		Comparator<Row> order = Comparator.comparingInt(Row::points).reversed()
				.thenComparing(Comparator.comparingInt(Row::buchholz).reversed())
				.thenComparing(Comparator.comparingDouble(Row::opponentsWinRate).reversed());
		sorted.sort(order); // stable: equal rows keep the players' order
		// Head-to-head between two players tied on everything.
		for (int i = 0; i + 1 < sorted.size(); i++) {
			Row a = sorted.get(i);
			Row b = sorted.get(i + 1);
			boolean tied = order.compare(a, b) == 0 && (i + 2 >= sorted.size() || order.compare(b, sorted.get(i + 2)) != 0)
					&& (i == 0 || order.compare(sorted.get(i - 1), a) != 0);
			if (tied && beat(results, b.player(), a.player())) {
				sorted.set(i, b);
				sorted.set(i + 1, a);
			}
		}
		return sorted;
	}

	private static boolean beat(Collection<Result> results, UUID winner, UUID loser) {
		int balance = 0;
		for (Result result : results) {
			if (result.player2() == null || result.winner() == null) {
				continue;
			}
			boolean between = (result.player1().equals(winner) && result.player2().equals(loser))
					|| (result.player1().equals(loser) && result.player2().equals(winner));
			if (between) {
				balance += result.winner().equals(winner) ? 1 : -1;
			}
		}
		return balance > 0;
	}

	/**
	 * The next round's pairing of {@code ranked} (active players, best first; for round 1, shuffled), avoiding the
	 * pairs of {@code results} and giving the bye to someone who never had one.
	 */
	public static Pairing pair(List<UUID> ranked, Collection<Result> results) {
		Set<String> met = new HashSet<>();
		Set<UUID> hadBye = new HashSet<>();
		for (Result result : results) {
			if (result.player2() == null) {
				hadBye.add(result.player1());
			} else {
				met.add(key(result.player1(), result.player2()));
			}
		}
		List<UUID> players = new ArrayList<>(ranked);
		UUID bye = null;
		if (players.size() % 2 == 1) {
			bye = players.get(players.size() - 1);
			for (int i = players.size() - 1; i >= 0; i--) {
				if (!hadBye.contains(players.get(i))) {
					bye = players.get(i);
					break;
				}
			}
			players.remove(bye);
		}
		List<UUID[]> pairs = new ArrayList<>();
		int[] budget = { SEARCH_LIMIT };
		if (!search(players, met, pairs, budget)) {
			pairs.clear();
			search(players, Set.of(), pairs, new int[] { Integer.MAX_VALUE }); // rematches can't all be avoided
		}
		return new Pairing(pairs, bye);
	}

	private static boolean search(List<UUID> left, Set<String> met, List<UUID[]> pairs, int[] budget) {
		if (left.isEmpty()) {
			return true;
		}
		if (--budget[0] < 0) {
			return false;
		}
		UUID first = left.get(0);
		for (int i = 1; i < left.size(); i++) {
			UUID other = left.get(i);
			if (met.contains(key(first, other))) {
				continue;
			}
			List<UUID> rest = new ArrayList<>(left);
			rest.remove(i);
			rest.remove(0);
			pairs.add(new UUID[] { first, other });
			if (search(rest, met, pairs, budget)) {
				return true;
			}
			pairs.remove(pairs.size() - 1);
			if (budget[0] < 0) {
				return false;
			}
		}
		return false;
	}

	private static String key(UUID a, UUID b) {
		return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
	}
}
