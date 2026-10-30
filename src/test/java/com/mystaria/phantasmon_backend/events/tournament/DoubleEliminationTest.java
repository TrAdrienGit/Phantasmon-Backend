package com.mystaria.phantasmon_backend.events.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Plays whole brackets of 2 to 40 players at random: everyone but the champion loses exactly twice, waves in order. */
class DoubleEliminationTest {

	private static final UUID NOBODY = DoubleElimination.NOBODY;

	@Test
	void everyBracketEndsWithOneChampionAndTwoLossesForEveryoneElse() {
		Random random = new Random(7);
		for (int size = 2; size <= 40; size++) {
			List<UUID> players = IntStream.range(0, size).mapToObj(i -> new UUID(1, i + 1)).toList();
			Map<String, UUID[]> slots = new HashMap<>();
			Map<String, Integer> waves = new HashMap<>();
			for (DoubleElimination.Node node : DoubleElimination.shape(size)) {
				slots.put(key(node.bracket(), node.round(), node.position()), new UUID[2]);
				waves.put(key(node.bracket(), node.round(), node.position()), node.wave());
			}
			DoubleElimination.seed(players).forEach((p, pair) -> slots.put(key(DoubleElimination.WINNERS, 1, p), pair.clone()));
			Map<UUID, Integer> losses = new HashMap<>();
			Set<String> done = new HashSet<>();
			UUID champion = null;
			for (int wave = 1; wave <= DoubleElimination.waves(size); wave++) {
				boolean progress = true;
				while (progress) {
					progress = false;
					for (Map.Entry<String, UUID[]> entry : slots.entrySet()) {
						String key = entry.getKey();
						UUID[] pair = entry.getValue();
						if (done.contains(key) || pair[0] == null || pair[1] == null) {
							continue;
						}
						boolean real = !pair[0].equals(NOBODY) && !pair[1].equals(NOBODY);
						if (real && waves.get(key) > wave) {
							continue; // ready, but its wave is not open yet
						}
						UUID winner = pair[1].equals(NOBODY) ? pair[0] : pair[0].equals(NOBODY) ? pair[1]
								: pair[random.nextInt(2)];
						UUID loser = winner.equals(pair[0]) ? pair[1] : pair[0];
						done.add(key);
						progress = true;
						String[] parts = key.split(":");
						if (!loser.equals(NOBODY)) {
							losses.merge(loser, 1, Integer::sum);
						}
						if (parts[0].equals(DoubleElimination.FINAL)) {
							if (winner.equals(pair[0])) {
								champion = winner;
								slots.put(key(DoubleElimination.RESET, 1, 0), new UUID[] { NOBODY, NOBODY });
							} else {
								slots.put(key(DoubleElimination.RESET, 1, 0), new UUID[] { pair[0], pair[1] });
							}
							continue;
						}
						if (parts[0].equals(DoubleElimination.RESET)) {
							if (!winner.equals(NOBODY)) {
								champion = winner;
							}
							continue;
						}
						int round = Integer.parseInt(parts[1]);
						int position = Integer.parseInt(parts[2]);
						place(slots, DoubleElimination.winnerGoes(size, parts[0], round, position), winner);
						DoubleElimination.Destination down = DoubleElimination.loserGoes(size, parts[0], round, position);
						if (down != null) {
							place(slots, down, loser);
						}
					}
				}
			}
			assertThat(done).as("every match decided (size %d)", size).hasSize(slots.size());
			assertThat(champion).as("a champion (size %d)", size).isNotNull();
			List<UUID> twice = new ArrayList<>(players);
			twice.remove(champion);
			for (UUID player : twice) {
				assertThat(losses.getOrDefault(player, 0)).as("losses of a player (size %d)", size).isEqualTo(2);
			}
			assertThat(losses.getOrDefault(champion, 0)).isLessThanOrEqualTo(1);
		}
	}

	private static void place(Map<String, UUID[]> slots, DoubleElimination.Destination to, UUID player) {
		UUID[] pair = slots.get(key(to.bracket(), to.round(), to.position()));
		assertThat(pair).as("destination %s exists", to).isNotNull();
		assertThat(pair[to.slot()]).as("slot %s free", to).isNull();
		pair[to.slot()] = player;
	}

	private static String key(String bracket, int round, int position) {
		return bracket + ":" + round + ":" + position;
	}
}
