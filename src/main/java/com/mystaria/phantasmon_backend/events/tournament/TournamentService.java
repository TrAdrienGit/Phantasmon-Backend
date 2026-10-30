package com.mystaria.phantasmon_backend.events.tournament;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.battle.format.BattleFormats;
import com.mystaria.phantasmon_backend.battle.format.ShowdownDataSource;
import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerRepository;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

/**
 * Tournaments before they start (plan-tournois.md, phase T1): created by an admin, players register in the
 * registration window (admins any time), check in once an admin opens the check-in. Every change is pushed to every
 * connected client ({@code EventsTournamentChanged}), which reads the tournament again.
 */
@Service
public class TournamentService {

	private static final Logger log = LoggerFactory.getLogger(TournamentService.class);
	static final Pattern NAME = Pattern.compile("[a-z0-9_]{2,32}");

	/** A tournament as the API shows it. */
	public record View(String name, String kind, int bestOf, String formatId, String formatName, String status,
			Instant startsAt, Instant registrationOpensAt, Instant registrationClosesAt, boolean registrationOpen,
			Integer plannedRounds, int currentRound, List<PlayerView> players, List<MatchView> matches,
			List<StandingView> standings) {
	}

	/** A match: its players' names, games won by each, winner (null until decided). */
	public record MatchView(int round, String bracket, int position, int wave, String player1, String player2, int wins1,
			int wins2, String winner, String status, boolean forfeit) {
	}

	/** A line of the Swiss standings, best first. */
	public record StandingView(int rank, String username, int points, int wins, int losses, int buchholz,
			double opponentsWinRate, boolean dropped) {
	}

	public record PlayerView(UUID uuid, String username, boolean checkedIn, boolean dropped) {
	}

	/** {@code POST /admin/tournaments}; dates optional (ISO-8601). */
	public record CreateRequest(String name, String kind, Integer bestOf, String formatId, Instant startsAt,
			Instant registrationOpensAt, Instant registrationClosesAt) {
	}

	/** {@code POST /admin/tournaments/{name}/schedule}: the dates to change (absent: unchanged). */
	public record ScheduleRequest(Instant startsAt, Instant registrationOpensAt, Instant registrationClosesAt) {
	}

	private final TournamentRepository tournaments;
	private final TournamentPlayerRepository players;
	private final PlayerRepository playerRepository;
	private final ShowdownDataSource showdown;
	private final SessionRegistry sessionRegistry;
	private final TournamentMatchRepository matches;
	private final com.mystaria.phantasmon_backend.battle.LiveBattleService liveBattleService;

	public TournamentService(TournamentRepository tournaments, TournamentPlayerRepository players,
			PlayerRepository playerRepository, ShowdownDataSource showdown, SessionRegistry sessionRegistry,
			TournamentMatchRepository matches, com.mystaria.phantasmon_backend.battle.LiveBattleService liveBattleService) {
		this.liveBattleService = liveBattleService;
		this.tournaments = tournaments;
		this.players = players;
		this.matches = matches;
		this.playerRepository = playerRepository;
		this.showdown = showdown;
		this.sessionRegistry = sessionRegistry;
	}

	// ---------------------------------------------------------------- reading

	/** The tournaments not over yet, then the last 10 over. */
	@Transactional(readOnly = true)
	public List<View> list() {
		List<Tournament> all = tournaments.findAllByOrderByCreatedAtDesc();
		List<Tournament> shown = new java.util.ArrayList<>(all.stream().filter(t -> !t.over()).toList());
		all.stream().filter(Tournament::over).limit(10).forEach(shown::add);
		return shown.stream().map(this::view).toList();
	}

	@Transactional(readOnly = true)
	public View get(String name) {
		return view(require(name));
	}

	// ---------------------------------------------------------------- players

	@Transactional
	public View register(UUID playerUuid, String name) {
		Tournament tournament = require(name);
		if (!registrationOpen(tournament)) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_REGISTRATION_CLOSED", Map.of("tournament", tournament.getName()));
		}
		return add(tournament, player(playerUuid), null);
	}

	@Transactional
	public View unregister(UUID playerUuid, String name) {
		Tournament tournament = require(name);
		if (Tournament.RUNNING.equals(tournament.getStatus()) || tournament.over()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_STARTED", Map.of("tournament", tournament.getName()));
		}
		TournamentPlayer entry = players.findById(new TournamentPlayer.Key(tournament.getUuid(), playerUuid)).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_TOURNAMENT_NOT_REGISTERED", Map.of("tournament", tournament.getName())));
		players.delete(entry);
		return changed(tournament);
	}

	@Transactional
	public View checkIn(UUID playerUuid, String name) {
		return checkIn(playerUuid, name, null);
	}

	/**
	 * Check-in; {@code party}: the player's Cobblemon party as their client sent it (optional). One of the two teams,
	 * the active Ghost one or the Cobblemon party, must fit the tournament's format (Adrien 2026-10-30).
	 */
	@Transactional
	public View checkIn(UUID playerUuid, String name, Map<String, Object> party) {
		Tournament tournament = require(name);
		if (!Tournament.CHECK_IN.equals(tournament.getStatus())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_NO_CHECK_IN", Map.of("tournament", tournament.getName()));
		}
		TournamentPlayer entry = players.findById(new TournamentPlayer.Key(tournament.getUuid(), playerUuid)).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_TOURNAMENT_NOT_REGISTERED", Map.of("tournament", tournament.getName())));
		// The teams checked against the tournament's format now, not at the first battle (Adrien 2026-10-30).
		List<String> issues = liveBattleService.ghostTeamIssues(playerUuid, tournament.getFormatId());
		List<String> partyIssues = liveBattleService.cobblemonPartyIssues(playerUuid, party, tournament.getFormatId());
		if (!issues.isEmpty() && (partyIssues == null || !partyIssues.isEmpty())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_TEAM_INVALID", Map.of("tournament", tournament.getName(),
					"issues", issues.subList(0, Math.min(5, issues.size()))));
		}
		entry.setCheckedIn(true);
		return changed(tournament);
	}

	// ---------------------------------------------------------------- admin

	@Transactional
	public View create(UUID adminUuid, CreateRequest request) {
		String name = request.name() == null ? "" : request.name().strip().toLowerCase(Locale.ROOT);
		if (!NAME.matcher(name).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_TOURNAMENT_INVALID_NAME", Map.of("name", name));
		}
		String kind = request.kind() == null ? "" : request.kind().strip().toUpperCase(Locale.ROOT);
		if (!Tournament.SWISS.equals(kind) && !Tournament.BRACKET.equals(kind)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_TOURNAMENT_INVALID_KIND", Map.of("kind", kind));
		}
		int bestOf = request.bestOf() == null ? 1 : request.bestOf();
		if (bestOf != 1 && bestOf != 3 && bestOf != 5) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_TOURNAMENT_INVALID_BEST_OF", Map.of("best_of", bestOf));
		}
		String format = request.formatId() == null ? "" : request.formatId().strip().toLowerCase(Locale.ROOT);
		if (BattleFormats.FREE.equals(format) || !BattleFormats.OFFERED.contains(format)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_TOURNAMENT_INVALID_FORMAT", Map.of("format", format));
		}
		if (tournaments.findOpenByName(name).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_NAME_TAKEN", Map.of("tournament", name));
		}
		Tournament tournament = new Tournament(UUID.randomUUID(), name, kind, bestOf, format, adminUuid);
		tournament.setStartsAt(request.startsAt());
		tournament.setRegistrationOpensAt(request.registrationOpensAt());
		tournament.setRegistrationClosesAt(request.registrationClosesAt());
		tournaments.saveAndFlush(tournament);
		log.info("Admin {} created tournament {} ({}, best of {}, {})", adminUuid, name, kind, bestOf, format);
		return changed(tournament);
	}

	@Transactional
	public View schedule(String name, ScheduleRequest request) {
		Tournament tournament = requireNotOver(name);
		if (request.startsAt() != null) {
			tournament.setStartsAt(request.startsAt());
		}
		if (request.registrationOpensAt() != null) {
			tournament.setRegistrationOpensAt(request.registrationOpensAt());
		}
		if (request.registrationClosesAt() != null) {
			tournament.setRegistrationClosesAt(request.registrationClosesAt());
		}
		return changed(tournament);
	}

	/** An admin registers a player by name (one who has used Phantasmon), whatever the window. */
	@Transactional
	public View adminAdd(UUID adminUuid, String name, String username) {
		Tournament tournament = requireNotOver(name);
		Player player = playerRepository.findFirstByLastUsernameIgnoreCaseOrderByLastSeenAtDesc(username == null ? "" : username.strip())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_PLAYER_NOT_FOUND", Map.of("username", String.valueOf(username))));
		log.info("Admin {} registered {} in tournament {}", adminUuid, player.getLastUsername(), tournament.getName());
		return add(tournament, player, adminUuid);
	}

	/**
	 * An admin removes a player: before the start, the registration goes; once running, the player is dropped (no
	 * longer paired, the matches played still count).
	 */
	@Transactional
	public View adminRemove(UUID adminUuid, String name, String username) {
		Tournament tournament = requireNotOver(name);
		TournamentPlayer entry = players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid()).stream()
				.filter(p -> p.getUsername().equalsIgnoreCase(username == null ? "" : username.strip())).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_TOURNAMENT_NOT_REGISTERED",
						Map.of("tournament", tournament.getName(), "username", String.valueOf(username))));
		if (Tournament.RUNNING.equals(tournament.getStatus())) {
			entry.setDropped(true);
		} else {
			players.delete(entry);
		}
		log.info("Admin {} removed {} from tournament {}", adminUuid, entry.getUsername(), tournament.getName());
		return changed(tournament);
	}

	/** Registration → check-in: the registered players confirm they are there. */
	@Transactional
	public View openCheckIn(String name) {
		Tournament tournament = requireNotOver(name);
		if (!Tournament.REGISTRATION.equals(tournament.getStatus())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_WRONG_STEP", Map.of("tournament", tournament.getName(),
					"status", tournament.getStatus()));
		}
		tournament.setStatus(Tournament.CHECK_IN);
		return changed(tournament);
	}

	@Transactional
	public View cancel(UUID adminUuid, String name) {
		Tournament tournament = requireNotOver(name);
		tournament.setStatus(Tournament.CANCELLED);
		log.info("Admin {} cancelled tournament {}", adminUuid, tournament.getName());
		return changed(tournament);
	}

	/**
	 * A cancelled tournament erased with everything tied to it (players, rounds, matches), gone from the list (Adrien
	 * 2026-10-30). Only a cancelled one: the most recent cancelled tournament of that name.
	 */
	@Transactional
	public void purge(UUID adminUuid, String name) {
		String key = name == null ? "" : name.strip();
		Tournament tournament = tournaments.findAllByOrderByCreatedAtDesc().stream()
				.filter(t -> t.getName().equalsIgnoreCase(key) && Tournament.CANCELLED.equals(t.getStatus())).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_NOT_CANCELLED", Map.of("tournament", key)));
		tournaments.delete(tournament); // players and matches go with it (ON DELETE CASCADE)
		tournaments.flush();
		log.info("Admin {} erased cancelled tournament {}", adminUuid, tournament.getName());
		sessionRegistry.broadcast(WsMessage.of("EventsTournamentChanged", Map.of("tournament", tournament.getName())));
	}

	// ---------------------------------------------------------------- internals

	private View add(Tournament tournament, Player player, UUID adminUuid) {
		TournamentPlayer.Key key = new TournamentPlayer.Key(tournament.getUuid(), player.getUuid());
		if (players.existsById(key)) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_ALREADY_REGISTERED", Map.of("tournament", tournament.getName()));
		}
		TournamentPlayer entry = new TournamentPlayer(tournament.getUuid(), player.getUuid(), player.getLastUsername());
		// Registered during the check-in (late, or by an admin): there, so checked in.
		entry.setCheckedIn(Tournament.CHECK_IN.equals(tournament.getStatus()) || adminUuid != null && !Tournament.REGISTRATION.equals(tournament.getStatus()));
		players.save(entry);
		return changed(tournament);
	}

	boolean registrationOpen(Tournament tournament) {
		if (!Tournament.REGISTRATION.equals(tournament.getStatus()) && !Tournament.CHECK_IN.equals(tournament.getStatus())) {
			return false;
		}
		Instant now = Instant.now();
		return (tournament.getRegistrationOpensAt() == null || !now.isBefore(tournament.getRegistrationOpensAt()))
				&& (tournament.getRegistrationClosesAt() == null || now.isBefore(tournament.getRegistrationClosesAt()));
	}

	private Player player(UUID playerUuid) {
		return playerRepository.findById(playerUuid).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_PLAYER_NOT_FOUND", Map.of("player_uuid", playerUuid)));
	}

	Tournament require(String name) {
		String key = name == null ? "" : name.strip();
		return tournaments.findOpenByName(key).or(() -> tournaments.findAllByOrderByCreatedAtDesc().stream()
				.filter(t -> t.getName().equalsIgnoreCase(key)).findFirst())
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_TOURNAMENT_NOT_FOUND", Map.of("tournament", key)));
	}

	Tournament requireNotOver(String name) {
		Tournament tournament = require(name);
		if (tournament.over()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_TOURNAMENT_OVER", Map.of("tournament", tournament.getName()));
		}
		return tournament;
	}

	View changed(Tournament tournament) {
		tournaments.saveAndFlush(tournament);
		sessionRegistry.broadcast(WsMessage.of("EventsTournamentChanged", Map.of("tournament", tournament.getName())));
		return view(tournament);
	}

	View view(Tournament tournament) {
		BattleFormats.Format format = showdown.formats().get(tournament.getFormatId());
		List<TournamentPlayer> registered = players.findByKeyTournamentUuidOrderByRegisteredAtAsc(tournament.getUuid());
		List<PlayerView> entries = registered.stream()
				.map(p -> new PlayerView(p.playerUuid(), p.getUsername(), p.isCheckedIn(), p.isDropped())).toList();
		Map<UUID, TournamentPlayer> byUuid = new java.util.HashMap<>();
		registered.forEach(p -> byUuid.put(p.playerUuid(), p));
		// null: not known yet; "-": nobody (a bye in a bracket).
		java.util.function.Function<UUID, String> nameOf = uuid -> uuid == null ? null
				: DoubleElimination.NOBODY.equals(uuid) ? "-" : byUuid.containsKey(uuid) ? byUuid.get(uuid).getUsername() : "?";
		List<TournamentMatch> all = matches.findByTournamentUuidOrderByRoundAscPositionAsc(tournament.getUuid());
		List<MatchView> matchViews = all.stream().map(m -> new MatchView(m.getRound(), m.getBracket(), m.getPosition(), m.getWave(),
				nameOf.apply(m.getPlayer1()), nameOf.apply(m.getPlayer2()), m.getWins1(), m.getWins2(), nameOf.apply(m.getWinner()),
				m.getStatus(), m.isForfeit())).toList();
		List<StandingView> standings = new java.util.ArrayList<>();
		if (Tournament.SWISS.equals(tournament.getKind()) && !all.isEmpty()) {
			List<Swiss.Row> rows = Swiss.standings(registered.stream().map(TournamentPlayer::playerUuid).toList(), results(all));
			for (int i = 0; i < rows.size(); i++) {
				Swiss.Row row = rows.get(i);
				standings.add(new StandingView(i + 1, nameOf.apply(row.player()), row.points(), row.wins(), row.losses(),
						row.buchholz(), Math.round(row.opponentsWinRate() * 1000) / 1000.0,
						byUuid.containsKey(row.player()) && byUuid.get(row.player()).isDropped()));
			}
		}
		if (Tournament.BRACKET.equals(tournament.getKind()) && !all.isEmpty()) {
			standings = bracketStandings(registered, all, nameOf);
		}
		return new View(tournament.getName(), tournament.getKind(), tournament.getBestOf(), tournament.getFormatId(),
				format == null ? tournament.getFormatId() : format.name(), tournament.getStatus(), tournament.getStartsAt(),
				tournament.getRegistrationOpensAt(), tournament.getRegistrationClosesAt(), registrationOpen(tournament),
				tournament.getPlannedRounds() == null ? null : tournament.getPlannedRounds().intValue(),
				tournament.getCurrentRound(), entries, matchViews, standings);
	}

	/**
	 * A bracket's placements: the champion (reset final's winner, else the grand final's), then the players with
	 * fewer losses, then those who went further. {@code points}: matches won.
	 */
	private static List<StandingView> bracketStandings(List<TournamentPlayer> registered, List<TournamentMatch> all,
			java.util.function.Function<UUID, String> nameOf) {
		Map<UUID, int[]> stats = new java.util.HashMap<>(); // wins, losses, last wave played
		registered.forEach(p -> stats.put(p.playerUuid(), new int[3]));
		UUID champion = null;
		for (TournamentMatch m : all) {
			boolean real = m.getPlayer1() != null && m.getPlayer2() != null && !DoubleElimination.NOBODY.equals(m.getPlayer1())
					&& !DoubleElimination.NOBODY.equals(m.getPlayer2());
			if (!real || !TournamentMatch.DONE.equals(m.getStatus()) || m.getWinner() == null) {
				continue;
			}
			UUID loser = m.opponentOf(m.getWinner());
			if (stats.containsKey(m.getWinner())) {
				stats.get(m.getWinner())[0]++;
				stats.get(m.getWinner())[2] = Math.max(stats.get(m.getWinner())[2], m.getWave());
			}
			if (stats.containsKey(loser)) {
				stats.get(loser)[1]++;
				stats.get(loser)[2] = Math.max(stats.get(loser)[2], m.getWave());
			}
			if (DoubleElimination.RESET.equals(m.getBracket())
					|| DoubleElimination.FINAL.equals(m.getBracket()) && m.getWinner().equals(m.getPlayer1())) {
				champion = m.getWinner();
			}
		}
		UUID first = champion;
		List<UUID> order = new java.util.ArrayList<>(stats.keySet());
		java.util.Comparator<UUID> ranking = java.util.Comparator.<UUID, Boolean>comparing(u -> !u.equals(first))
				.thenComparingInt(u -> stats.get(u)[1]).thenComparing(u -> -stats.get(u)[2]);
		order.sort(ranking);
		Map<UUID, TournamentPlayer> byUuid = new java.util.HashMap<>();
		registered.forEach(p -> byUuid.put(p.playerUuid(), p));
		List<StandingView> rows = new java.util.ArrayList<>();
		int rank = 0;
		UUID previous = null;
		for (int i = 0; i < order.size(); i++) {
			UUID u = order.get(i);
			if (previous == null || ranking.compare(previous, u) != 0) {
				rank = i + 1;
			}
			previous = u;
			int[] st = stats.get(u);
			rows.add(new StandingView(rank, nameOf.apply(u), st[0], st[0], st[1], 0, 0, byUuid.get(u).isDropped()));
		}
		return rows;
	}

	/** The decided matches, as the Swiss logic reads them. */
	static List<Swiss.Result> results(List<TournamentMatch> all) {
		return all.stream().filter(m -> TournamentMatch.DONE.equals(m.getStatus()) && m.getWinner() != null)
				.map(m -> new Swiss.Result(m.getPlayer1(), m.getPlayer2(), m.getWinner())).toList();
	}
}
