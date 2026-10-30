package com.mystaria.phantasmon_backend.events.tournament;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mystaria.phantasmon_backend.battle.LiveBattleService;
import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * A running tournament (plan-tournois.md, phase T2: weekly Swiss rounds). An admin starts it (the players who did not
 * check in are left out), then announces each round: the backend pairs the players ({@link Swiss}), opens the Ghost
 * lobby of each match itself with the tournament's format, counts the games (best of 1, 3 or 5) and decides the
 * match. Nothing moves on alone: the next round, a forfeit, a corrected result, a new invitation are an admin's.
 *
 * <p>Players are told by {@code EventsTournamentAnnounce} ({@code kind}: {@code ROUND}, {@code GAME}, {@code RESULT},
 * {@code MATCH_WAITING}, {@code ROUND_DONE}, {@code FINISHED}), shown in their chat and as a title.
 */
@Service
public class TournamentRunService implements LiveBattleService.TournamentHook {

	private static final Logger log = LoggerFactory.getLogger(TournamentRunService.class);
	/** The next game of a series opens after the players saw the end of the last one. */
	private static final long NEXT_GAME_DELAY_SECONDS = 8;

	private final TournamentService tournamentService;
	private final TournamentRepository tournaments;
	private final TournamentPlayerRepository players;
	private final TournamentMatchRepository matches;
	private final LiveBattleService liveBattleService;
	private final SessionRegistry sessionRegistry;
	private final TransactionTemplate transaction;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "tournament-games");
		thread.setDaemon(true);
		return thread;
	});

	public TournamentRunService(TournamentService tournamentService, TournamentRepository tournaments,
			TournamentPlayerRepository players, TournamentMatchRepository matches, LiveBattleService liveBattleService,
			SessionRegistry sessionRegistry, PlatformTransactionManager transactionManager) {
		this.tournamentService = tournamentService;
		this.tournaments = tournaments;
		this.players = players;
		this.matches = matches;
		this.liveBattleService = liveBattleService;
		this.sessionRegistry = sessionRegistry;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@PostConstruct
	void hook() {
		liveBattleService.setTournamentHook(this);
	}

	@PreDestroy
	void stop() {
		scheduler.shutdownNow();
	}

	// ---------------------------------------------------------------- admin

	/** Registration or check-in → running: those who did not check in (when a check-in was held) are left out. */
	@Transactional
	public TournamentService.View start(UUID adminUuid, String name) {
		Tournament tournament = tournamentService.requireNotOver(name);
		if (!Tournament.REGISTRATION.equals(tournament.getStatus()) && !Tournament.CHECK_IN.equals(tournament.getStatus())) {
			throw wrongStep(tournament);
		}
		List<TournamentPlayer> entries = players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid());
		if (Tournament.CHECK_IN.equals(tournament.getStatus())) {
			List<TournamentPlayer> absent = entries.stream().filter(p -> !p.isCheckedIn()).toList();
			players.deleteAll(absent);
			entries = entries.stream().filter(TournamentPlayer::isCheckedIn).toList();
		}
		if (entries.size() < 2) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_NOT_ENOUGH_PLAYERS", Map.of("tournament", tournament.getName()));
		}
		tournament.setStatus(Tournament.RUNNING);
		if (Tournament.BRACKET.equals(tournament.getKind())) {
			buildBracket(tournament, entries.stream().map(TournamentPlayer::playerUuid).toList());
			tournament.setPlannedRounds((short) DoubleElimination.waves(entries.size()));
		} else {
			tournament.setPlannedRounds((short) Swiss.plannedRounds(entries.size()));
		}
		log.info("Admin {} started tournament {} with {} players, {} rounds", adminUuid, tournament.getName(), entries.size(),
				tournament.getPlannedRounds());
		return tournamentService.changed(tournament);
	}

	/** The next Swiss round: every match of the current one must be decided; within the rounds planned. */
	@Transactional
	public TournamentService.View nextRound(UUID adminUuid, String name) {
		Tournament tournament = running(name);
		if (Tournament.BRACKET.equals(tournament.getKind())) {
			return nextWave(adminUuid, tournament);
		}
		List<TournamentMatch> all = matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid());
		if (all.stream().anyMatch(m -> !TournamentMatch.DONE.equals(m.getStatus()))) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_ROUND_NOT_OVER", Map.of("tournament", tournament.getName()));
		}
		if (tournament.getPlannedRounds() != null && tournament.getCurrentRound() >= tournament.getPlannedRounds()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_ROUNDS_DONE", Map.of("tournament", tournament.getName()));
		}
		List<TournamentPlayer> entries = players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid());
		List<UUID> active = new ArrayList<>(entries.stream().filter(p -> !p.isDropped()).map(TournamentPlayer::playerUuid).toList());
		if (active.size() < 2) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_NOT_ENOUGH_PLAYERS", Map.of("tournament", tournament.getName()));
		}
		List<Swiss.Result> results = TournamentService.results(all);
		List<UUID> ranked;
		if (tournament.getCurrentRound() == 0) {
			Collections.shuffle(active);
			ranked = active;
		} else {
			ranked = Swiss.standings(entries.stream().map(TournamentPlayer::playerUuid).toList(), results).stream()
					.map(Swiss.Row::player).filter(active::contains).toList();
		}
		Swiss.Pairing pairing = Swiss.pair(ranked, results);
		int round = tournament.getCurrentRound() + 1;
		tournament.setCurrentRound((short) round);
		List<TournamentMatch> created = new ArrayList<>();
		int position = 0;
		for (UUID[] pair : pairing.pairs()) {
			created.add(new TournamentMatch(UUID.randomUUID(), tournament.getUuid(), round, "SWISS", position++, pair[0], pair[1]));
		}
		if (pairing.bye() != null) {
			TournamentMatch bye = new TournamentMatch(UUID.randomUUID(), tournament.getUuid(), round, "SWISS", position, pairing.bye(), null);
			bye.setWinner(pairing.bye());
			bye.setStatus(TournamentMatch.DONE);
			created.add(bye);
		}
		matches.saveAllAndFlush(created);
		log.info("Admin {} announced round {} of tournament {}: {} matches", adminUuid, round, tournament.getName(), created.size());
		Map<UUID, String> names = names(tournament);
		for (TournamentMatch match : created) {
			for (UUID player : new UUID[] { match.getPlayer1(), match.getPlayer2() }) {
				if (player != null) {
					UUID opponent = match.opponentOf(player);
					announce(player, tournament, "ROUND", Map.of("round", round, "of", tournament.getPlannedRounds(),
							"opponent", opponent == null ? "" : names.getOrDefault(opponent, "?"), "bye", opponent == null));
				}
			}
		}
		// Paired, not forced into a lobby (Adrien 2026-10-30): the players launch their battle when and where they want.
		return tournamentService.changed(tournament);
	}

	/** One more round, or one fewer (never below the rounds already announced). */
	@Transactional
	public TournamentService.View changeRounds(String name, int delta) {
		Tournament tournament = running(name);
		int planned = (tournament.getPlannedRounds() == null ? 0 : tournament.getPlannedRounds()) + delta;
		tournament.setPlannedRounds((short) Math.max(Math.max(1, tournament.getCurrentRound()), planned));
		return tournamentService.changed(tournament);
	}

	/**
	 * A match of the current round decided by an admin: {@code username} wins it (or, {@code forfeit}, loses it by
	 * forfeit: the opponent wins). Also corrects a match already decided.
	 */
	@Transactional
	public TournamentService.View setResult(UUID adminUuid, String name, String username, boolean forfeit) {
		Tournament tournament = running(name);
		UUID player = playerNamed(tournament, username);
		TournamentMatch match = currentMatch(tournament, player);
		if (match.bye()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_BYE", Map.of("tournament", tournament.getName()));
		}
		if (Tournament.BRACKET.equals(tournament.getKind()) && TournamentMatch.DONE.equals(match.getStatus())) {
			// Its winner and loser already went on in the bracket.
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_TOO_LATE", Map.of("tournament", tournament.getName()));
		}
		UUID winner = forfeit ? match.opponentOf(player) : player;
		match.setWinner(winner);
		match.setForfeit(forfeit);
		match.setStatus(TournamentMatch.DONE);
		match.setEditedBy(adminUuid);
		matches.saveAndFlush(match);
		log.info("Admin {} set match {} of tournament {}: {} wins{}", adminUuid, match.getUuid(), tournament.getName(), winner,
				forfeit ? " (forfeit)" : "");
		decided(tournament, match);
		return tournamentService.changed(tournament);
	}

	/** The lobby of a player's current match opened again (a player was away, a lobby was left). */
	@Transactional
	public TournamentService.View reinvite(String name, String username) {
		Tournament tournament = running(name);
		TournamentMatch match = currentMatch(tournament, playerNamed(tournament, username));
		if (TournamentMatch.DONE.equals(match.getStatus()) || match.bye()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_MATCH_DONE", Map.of("tournament", tournament.getName()));
		}
		openGame(match);
		return tournamentService.changed(tournament);
	}

	/** The tournament over: the standings stay as they are. */
	@Transactional
	public TournamentService.View finish(UUID adminUuid, String name) {
		Tournament tournament = running(name);
		tournament.setStatus(Tournament.FINISHED);
		TournamentService.View view = tournamentService.changed(tournament);
		String first = view.standings().isEmpty() ? "?" : view.standings().get(0).username();
		for (TournamentPlayer entry : players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid())) {
			int rank = view.standings().stream().filter(s -> s.username().equals(entry.getUsername())).map(TournamentService.StandingView::rank)
					.findFirst().orElse(0);
			announce(entry.playerUuid(), tournament, "FINISHED", Map.of("winner", first, "rank", rank));
		}
		log.info("Admin {} finished tournament {}, won by {}", adminUuid, tournament.getName(), first);
		return view;
	}

	// ---------------------------------------------------------------- games

	/** Opens the lobby of the match's next game; if a player is away or busy, the match waits for an admin. */
	private void openGame(TournamentMatch match) {
		Tournament tournament = tournaments.findById(match.getTournamentUuid()).orElseThrow();
		boolean opened = liveBattleService.openTournamentLobby(match.getUuid(), match.getPlayer1(), match.getPlayer2(),
				tournament.getFormatId());
		match.setStatus(opened ? TournamentMatch.PLAYING : TournamentMatch.PENDING);
		matches.saveAndFlush(match);
		if (!opened) {
			Map<UUID, String> names = names(tournament);
			for (UUID player : new UUID[] { match.getPlayer1(), match.getPlayer2() }) {
				announce(player, tournament, "MATCH_WAITING", Map.of("opponent", names.getOrDefault(match.opponentOf(player), "?")));
			}
		}
	}

	@Override
	public void onGameEnded(UUID matchUuid, UUID winnerUuid, String reason) {
		transaction.executeWithoutResult(status -> {
			TournamentMatch match = matches.findById(matchUuid).orElse(null);
			if (match == null || TournamentMatch.DONE.equals(match.getStatus())) {
				return;
			}
			Tournament tournament = tournaments.findById(match.getTournamentUuid()).orElseThrow();
			if (winnerUuid == null || !match.involves(winnerUuid)) {
				// A draw (disconnection, backend stopping, admin stop): not counted, an admin decides or invites again.
				match.setStatus(TournamentMatch.PENDING);
				matches.saveAndFlush(match);
				Map<UUID, String> names = names(tournament);
				for (UUID player : new UUID[] { match.getPlayer1(), match.getPlayer2() }) {
					announce(player, tournament, "MATCH_WAITING", Map.of("opponent", names.getOrDefault(match.opponentOf(player), "?")));
				}
				tournamentService.changed(tournament);
				return;
			}
			if (winnerUuid.equals(match.getPlayer1())) {
				match.setWins1((short) (match.getWins1() + 1));
			} else {
				match.setWins2((short) (match.getWins2() + 1));
			}
			int needed = tournament.getBestOf() / 2 + 1;
			if (match.getWins1() >= needed || match.getWins2() >= needed) {
				match.setWinner(winnerUuid);
				match.setStatus(TournamentMatch.DONE);
				matches.saveAndFlush(match);
				decided(tournament, match);
			} else {
				matches.saveAndFlush(match);
				Map<UUID, String> names = names(tournament);
				for (UUID player : new UUID[] { match.getPlayer1(), match.getPlayer2() }) {
					boolean first = player.equals(match.getPlayer1());
					announce(player, tournament, "GAME", Map.of("opponent", names.getOrDefault(match.opponentOf(player), "?"),
							"own", first ? match.getWins1() : match.getWins2(), "their", first ? match.getWins2() : match.getWins1(),
							"next", match.getWins1() + match.getWins2() + 1));
				}
				// The next game: the players invite each other again.
				match.setStatus(TournamentMatch.PENDING);
				matches.saveAndFlush(match);
			}
			tournamentService.changed(tournament);
		});
	}

	@Override
	public UUID pairedOpponent(UUID playerUuid) {
		return transaction.execute(status -> matches.findOpenFor(playerUuid).stream().findFirst()
				.map(match -> match.opponentOf(playerUuid)).orElse(null));
	}

	@Override
	public LiveBattleService.Binding bindLobby(UUID playerA, UUID playerB) {
		return transaction.execute(status -> {
			TournamentMatch match = matches.findOpenFor(playerA).stream().filter(m -> m.involves(playerB)).findFirst().orElse(null);
			if (match == null) {
				return null;
			}
			match.setStatus(TournamentMatch.PLAYING);
			matches.saveAndFlush(match);
			Tournament tournament = tournaments.findById(match.getTournamentUuid()).orElseThrow();
			tournamentService.changed(tournament);
			return new LiveBattleService.Binding(match.getUuid(), tournament.getFormatId());
		});
	}

	@Override
	public void onLobbyCancelled(UUID matchUuid, String reason) {
		transaction.executeWithoutResult(status -> matches.findById(matchUuid)
				.filter(m -> TournamentMatch.PLAYING.equals(m.getStatus())).ifPresent(match -> {
					match.setStatus(TournamentMatch.PENDING);
					matches.saveAndFlush(match);
					Tournament tournament = tournaments.findById(match.getTournamentUuid()).orElseThrow();
					Map<UUID, String> names = names(tournament);
					for (UUID player : new UUID[] { match.getPlayer1(), match.getPlayer2() }) {
						announce(player, tournament, "MATCH_WAITING", Map.of("opponent", names.getOrDefault(match.opponentOf(player), "?")));
					}
					tournamentService.changed(tournament);
				}));
	}

	/** A match decided: both players told; the round over, everyone in the tournament told. */
	private void decided(Tournament tournament, TournamentMatch match) {
		boolean bracket = Tournament.BRACKET.equals(tournament.getKind());
		if (bracket) {
			advance(tournament, match);
			settleWalkovers(tournament);
		}
		Map<UUID, String> names = names(tournament);
		UUID loser = match.opponentOf(match.getWinner());
		Map<String, Object> result = new HashMap<>();
		result.put("winner", names.getOrDefault(match.getWinner(), "?"));
		result.put("loser", names.getOrDefault(loser, "?"));
		result.put("score", Math.max(match.getWins1(), match.getWins2()) + "-" + Math.min(match.getWins1(), match.getWins2()));
		result.put("forfeit", match.isForfeit());
		List<TournamentMatch> round = matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid()).stream()
				.filter(m -> bracket ? m.getWave() <= tournament.getCurrentRound() : m.getRound() == tournament.getCurrentRound()).toList();
		for (TournamentPlayer entry : players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid())) {
			if (match.involves(entry.playerUuid())) {
				Map<String, Object> mine = new HashMap<>(result);
				mine.put("won", entry.playerUuid().equals(match.getWinner()));
				announce(entry.playerUuid(), tournament, "RESULT", mine);
			}
		}
		if (round.stream().allMatch(m -> TournamentMatch.DONE.equals(m.getStatus()))) {
			boolean last = bracket ? matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid()).stream()
					.allMatch(m -> TournamentMatch.DONE.equals(m.getStatus()))
					: tournament.getPlannedRounds() != null && tournament.getCurrentRound() >= tournament.getPlannedRounds();
			for (TournamentPlayer entry : players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid())) {
				announce(entry.playerUuid(), tournament, "ROUND_DONE", Map.of("round", tournament.getCurrentRound(), "last", last));
			}
		}
	}

	// ---------------------------------------------------------------- bracket (T4)

	/** Every match of the double elimination bracket, round 1 seeded at random; byes settled at once. */
	private void buildBracket(Tournament tournament, List<UUID> entrants) {
		List<UUID> seeded = new ArrayList<>(entrants);
		Collections.shuffle(seeded);
		Map<Integer, UUID[]> firstRound = DoubleElimination.seed(seeded);
		List<TournamentMatch> created = new ArrayList<>();
		for (DoubleElimination.Node node : DoubleElimination.shape(seeded.size())) {
			TournamentMatch match = new TournamentMatch(UUID.randomUUID(), tournament.getUuid(), node.round(), node.bracket(),
					node.position(), null, null);
			match.setWave((short) node.wave());
			if (DoubleElimination.WINNERS.equals(node.bracket()) && node.round() == 1) {
				match.setPlayer1(firstRound.get(node.position())[0]);
				match.setPlayer2(firstRound.get(node.position())[1]);
			}
			created.add(match);
		}
		matches.saveAllAndFlush(created);
		settleWalkovers(tournament);
	}

	/** Matches against nobody (byes, empty slots) decided at once, until none is left. */
	private void settleWalkovers(Tournament tournament) {
		boolean again = true;
		while (again) {
			again = false;
			for (TournamentMatch match : matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid())) {
				if (TournamentMatch.DONE.equals(match.getStatus()) || match.getPlayer1() == null || match.getPlayer2() == null) {
					continue;
				}
				boolean empty1 = DoubleElimination.NOBODY.equals(match.getPlayer1());
				boolean empty2 = DoubleElimination.NOBODY.equals(match.getPlayer2());
				if (!empty1 && !empty2) {
					continue;
				}
				match.setWinner(empty1 ? match.getPlayer2() : match.getPlayer1());
				match.setStatus(TournamentMatch.DONE);
				matches.saveAndFlush(match);
				advance(tournament, match);
				again = true;
			}
		}
	}

	/** The decided match's winner and loser placed in their next matches (the grand final decides the reset). */
	private void advance(Tournament tournament, TournamentMatch match) {
		int entrants = players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid()).size();
		UUID winner = match.getWinner();
		UUID loser = winner == null ? null : winner.equals(match.getPlayer1()) ? match.getPlayer2() : match.getPlayer1();
		if (DoubleElimination.FINAL.equals(match.getBracket())) {
			// The winners bracket's champion (slot 0) wins: no reset; else both have lost once: the reset final.
			boolean champion = winner != null && winner.equals(match.getPlayer1());
			place(tournament, new DoubleElimination.Destination(DoubleElimination.RESET, 1, 0, 0),
					champion ? DoubleElimination.NOBODY : match.getPlayer1());
			place(tournament, new DoubleElimination.Destination(DoubleElimination.RESET, 1, 0, 1),
					champion ? DoubleElimination.NOBODY : match.getPlayer2());
			return;
		}
		if (DoubleElimination.RESET.equals(match.getBracket())) {
			return;
		}
		DoubleElimination.Destination up = DoubleElimination.winnerGoes(entrants, match.getBracket(), match.getRound(), match.getPosition());
		if (up != null) {
			place(tournament, up, winner);
		}
		DoubleElimination.Destination down = DoubleElimination.loserGoes(entrants, match.getBracket(), match.getRound(), match.getPosition());
		if (down != null) {
			place(tournament, down, loser == null ? DoubleElimination.NOBODY : loser);
		}
	}

	private void place(Tournament tournament, DoubleElimination.Destination to, UUID player) {
		matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid()).stream()
				.filter(m -> m.getBracket().equals(to.bracket()) && m.getRound() == to.round() && m.getPosition() == to.position())
				.findFirst().ifPresent(m -> {
					if (to.slot() == 0) {
						m.setPlayer1(player);
					} else {
						m.setPlayer2(player);
					}
					matches.saveAndFlush(m);
				});
	}

	/** The next wave of the bracket: its matches become playable and their players are told. */
	private TournamentService.View nextWave(UUID adminUuid, Tournament tournament) {
		List<TournamentMatch> all = matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid());
		if (all.stream().anyMatch(m -> m.getWave() <= tournament.getCurrentRound() && !TournamentMatch.DONE.equals(m.getStatus()))) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_ROUND_NOT_OVER", Map.of("tournament", tournament.getName()));
		}
		if (all.stream().allMatch(m -> TournamentMatch.DONE.equals(m.getStatus()))) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_ROUNDS_DONE", Map.of("tournament", tournament.getName()));
		}
		// Waves with nothing left to play (walkovers only) are skipped.
		int wave = tournament.getCurrentRound();
		List<TournamentMatch> opened;
		do {
			wave++;
			int current = wave;
			opened = all.stream().filter(m -> m.getWave() == current && !TournamentMatch.DONE.equals(m.getStatus())).toList();
		} while (opened.isEmpty() && wave < DoubleElimination.waves(Math.max(2, players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid()).size())));
		tournament.setCurrentRound((short) wave);
		log.info("Admin {} opened wave {} of bracket {}: {} matches", adminUuid, wave, tournament.getName(), opened.size());
		Map<UUID, String> names = names(tournament);
		for (TournamentMatch match : opened) {
			for (UUID player : new UUID[] { match.getPlayer1(), match.getPlayer2() }) {
				announce(player, tournament, "ROUND", Map.of("round", wave, "of", tournament.getPlannedRounds(),
						"opponent", names.getOrDefault(match.opponentOf(player), "?"), "bye", false,
						"bracket", match.getBracket()));
			}
		}
		return tournamentService.changed(tournament);
	}

	// ---------------------------------------------------------------- internals

	private void announce(UUID player, Tournament tournament, String kind, Map<String, Object> details) {
		Map<String, Object> data = new HashMap<>(details);
		data.put("tournament", tournament.getName());
		data.put("kind", kind);
		sessionRegistry.send(player, WsMessage.of("EventsTournamentAnnounce", data));
	}

	private Tournament running(String name) {
		Tournament tournament = tournamentService.requireNotOver(name);
		if (!Tournament.RUNNING.equals(tournament.getStatus())) {
			throw wrongStep(tournament);
		}
		return tournament;
	}

	private static ApiException wrongStep(Tournament tournament) {
		return new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_WRONG_STEP", Map.of("tournament", tournament.getName(),
				"status", tournament.getStatus()));
	}

	private UUID playerNamed(Tournament tournament, String username) {
		String key = username == null ? "" : username.strip();
		return players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid()).stream()
				.filter(p -> p.getUsername().equalsIgnoreCase(key)).map(TournamentPlayer::playerUuid).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_TOURNAMENT_NOT_REGISTERED",
						Map.of("tournament", tournament.getName(), "username", key)));
	}

	private TournamentMatch currentMatch(Tournament tournament, UUID player) {
		if (Tournament.BRACKET.equals(tournament.getKind())) {
			return matches.findOpenFor(player).stream().filter(m -> m.getTournamentUuid().equals(tournament.getUuid())).findFirst()
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_TOURNAMENT_NO_MATCH",
							Map.of("tournament", tournament.getName())));
		}
		return matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid()).stream()
				.filter(m -> m.getRound() == tournament.getCurrentRound() && m.involves(player)).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_TOURNAMENT_NO_MATCH",
						Map.of("tournament", tournament.getName())));
	}

	private Map<UUID, String> names(Tournament tournament) {
		Map<UUID, String> names = new HashMap<>();
		players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid()).forEach(p -> names.put(p.playerUuid(), p.getUsername()));
		return names;
	}
}
