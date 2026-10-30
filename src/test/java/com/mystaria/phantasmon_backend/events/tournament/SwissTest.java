package com.mystaria.phantasmon_backend.events.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class SwissTest {

	private static List<UUID> players(int count) {
		return IntStream.range(0, count).mapToObj(i -> new UUID(0, i + 1)).toList();
	}

	@Test
	void plannedRoundsIsLog2RoundedUp() {
		assertThat(Swiss.plannedRounds(1)).isEqualTo(1);
		assertThat(Swiss.plannedRounds(2)).isEqualTo(1);
		assertThat(Swiss.plannedRounds(8)).isEqualTo(3);
		assertThat(Swiss.plannedRounds(9)).isEqualTo(4);
		assertThat(Swiss.plannedRounds(32)).isEqualTo(5);
		assertThat(Swiss.plannedRounds(100)).isEqualTo(7);
	}

	@Test
	void winnersMeetWinners() {
		List<UUID> p = players(4);
		List<Swiss.Result> results = List.of(new Swiss.Result(p.get(0), p.get(1), p.get(0)),
				new Swiss.Result(p.get(2), p.get(3), p.get(2)));
		List<UUID> ranked = Swiss.standings(p, results).stream().map(Swiss.Row::player).toList();
		Swiss.Pairing pairing = Swiss.pair(ranked, results);
		assertThat(pairing.bye()).isNull();
		assertThat(pairing.pairs()).anySatisfy(pair -> assertThat(Set.of(pair)).containsExactlyInAnyOrder(p.get(0), p.get(2)));
		assertThat(pairing.pairs()).anySatisfy(pair -> assertThat(Set.of(pair)).containsExactlyInAnyOrder(p.get(1), p.get(3)));
	}

	@Test
	void byeGoesToTheLowestWhoNeverHadOne() {
		List<UUID> p = players(3);
		List<Swiss.Result> results = List.of(new Swiss.Result(p.get(2), null, p.get(2)));
		Swiss.Pairing pairing = Swiss.pair(p, results);
		assertThat(pairing.bye()).isEqualTo(p.get(1));
		assertThat(pairing.pairs()).hasSize(1);
	}

	@Test
	void byeCountsAsAWinPoint() {
		List<UUID> p = players(2);
		List<Swiss.Row> rows = Swiss.standings(p, List.of(new Swiss.Result(p.get(1), null, p.get(1))));
		assertThat(rows.get(0).player()).isEqualTo(p.get(1));
		assertThat(rows.get(0).points()).isEqualTo(1);
	}

	@Test
	void buchholzBreaksTies() {
		List<UUID> p = players(4);
		// A beats B, C beats D, then B beats D: A, B and C have 1 point; A's and B's opponents have 1 point in all,
		// C's opponent D none: C is last of the three (and A, tied with B, beat B).
		List<Swiss.Result> results = List.of(new Swiss.Result(p.get(0), p.get(1), p.get(0)),
				new Swiss.Result(p.get(2), p.get(3), p.get(2)), new Swiss.Result(p.get(1), p.get(3), p.get(1)));
		List<Swiss.Row> rows = Swiss.standings(List.of(p.get(2), p.get(1), p.get(0), p.get(3)), results);
		assertThat(rows.get(0).player()).isEqualTo(p.get(0));
		assertThat(rows.get(2).player()).isEqualTo(p.get(2));
	}

	@Test
	void headToHeadBreaksAFullTie() {
		List<UUID> p = players(2);
		List<Swiss.Result> results = List.of(new Swiss.Result(p.get(0), p.get(1), p.get(1)),
				new Swiss.Result(p.get(0), p.get(1), p.get(1)), new Swiss.Result(p.get(0), p.get(1), p.get(0)));
		List<Swiss.Row> rows = Swiss.standings(p, results);
		assertThat(rows.get(0).player()).isEqualTo(p.get(1));
	}

	@Test
	void noRematchOverAWholeTournament() {
		Random random = new Random(42);
		for (int size : new int[] { 8, 13, 32, 57, 100 }) {
			List<UUID> p = new ArrayList<>(players(size));
			Collections.shuffle(p, random);
			List<Swiss.Result> results = new ArrayList<>();
			Set<String> met = new HashSet<>();
			for (int round = 0; round < Swiss.plannedRounds(size); round++) {
				List<UUID> ranked = round == 0 ? p : Swiss.standings(p, results).stream().map(Swiss.Row::player).toList();
				Swiss.Pairing pairing = Swiss.pair(ranked, results);
				assertThat(pairing.pairs().size() * 2 + (pairing.bye() == null ? 0 : 1)).isEqualTo(size);
				for (UUID[] pair : pairing.pairs()) {
					String key = pair[0].compareTo(pair[1]) < 0 ? pair[0] + "" + pair[1] : pair[1] + "" + pair[0];
					assertThat(met.add(key)).as("rematch in a %d-player tournament", size).isTrue();
					results.add(new Swiss.Result(pair[0], pair[1], random.nextBoolean() ? pair[0] : pair[1]));
				}
				if (pairing.bye() != null) {
					results.add(new Swiss.Result(pairing.bye(), null, pairing.bye()));
				}
			}
		}
	}
}
