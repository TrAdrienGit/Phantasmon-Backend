package com.mystaria.phantasmon_backend.battle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.admin.AdminService;
import com.mystaria.phantasmon_backend.battle.format.BattleFormats;
import com.mystaria.phantasmon_backend.battle.format.TeamValidator;
import com.mystaria.phantasmon_backend.hub.HubService;
import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerService;
import com.mystaria.phantasmon_backend.pokemon.PokemonResponse;
import com.mystaria.phantasmon_backend.pokemon.PokemonService;
import com.mystaria.phantasmon_backend.presence.PresenceService;
import com.mystaria.phantasmon_backend.websocket.GhostRecall;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Live Ghost battles (Phase 9, CAD Partie 2 §9.1/§9.2). The battle engine (Cobblemon's own, Showdown inside)
 * runs on the <b>host</b> player's client; the backend:
 * <ul>
 *   <li>handles the invitation, then the <b>lobby</b> (team preview, Showdown style): each player sees their own
 *   team in full and only the species / form / shininess / gender of the opponent's, picks their lead — never
 *   revealed to the opponent —, may switch between their Ghosts and a copy of their Cobblemon party, and gets
 *   ready; the battle starts when both are ready, each team reordered lead first. An optional lobby timer
 *   (150 s) readies whoever is not with their first Pokémon as lead, and turns the battle timer on;</li>
 *   <li>picks the host — the inviter for a pair's first battle, then alternating
 *   between the two players on every following battle (the CAD's anti-abuse guardrail);</li>
 *   <li>persists the session ({@code battle_sessions}, player A = host) with both team snapshots;</li>
 *   <li>relays, opaquely: the host's encoded Cobblemon battle packets to the guest ({@code BattlePacket}) and
 *   the guest's encoded choices to the host ({@code BattleChoice}) — each direction only from the right player;</li>
 *   <li>broadcasts the turn timer once either player turns it on (never off again, like Showdown);</li>
 *   <li>recalls both players' Ghosts when the battle starts, and refuses any send-out until it ends
 *   ({@code ERROR_GHOST_IN_BATTLE}, TODO-14);</li>
 *   <li>applies the result guardrails: only the host reports it, the winner is a participant (or nobody for a
 *   draw), the session must still be active; a player leaving forfeits; a disconnection is a draw (CAD Partie
 *   1 §44).</li>
 * </ul>
 * The host is the only one to receive the opponent's full team (it needs it to build the battle); the guest
 * only ever sees what the lobby preview and the battle itself reveal. In memory like presence; one coarse lock.
 */
@Service
@Slf4j
public class LiveBattleService {

	/** Number of battle intro cinematics the client knows (TODO-26); {@code BattleSessionStarted.intro} is in [0, INTRO_COUNT). */
	public static final int INTRO_COUNT = 5;

	static final int TIMER_SECONDS = 90;

	private final Map<UUID, LiveBattle> battlesByPlayer = new HashMap<>();
	/** Players watching a battle (spectators), at most one battle each. */
	private final Map<UUID, LiveBattle> battlesBySpectator = new HashMap<>();
	private final Map<UUID, Lobby> lobbiesByPlayer = new HashMap<>();
	private final Map<UUID, Invite> invites = new HashMap<>();

	private final BattleRepository battleRepository;
	private final PokemonService pokemonService;
	private final PlayerService playerService;
	private final SessionRegistry sessionRegistry;
	private final GhostRecall ghostRecall;
	private final CobblemonPartyParser partyParser;
	private final Clock clock;
	private final Duration inviteTtl;
	private final Duration lobbyTimer;
	private final com.mystaria.phantasmon_backend.battle.format.ShowdownDataSource showdown;
	private final AdminService adminService;
	private final PresenceService presenceService;
	private final HubService hubService;

	public LiveBattleService(BattleRepository battleRepository, PokemonService pokemonService, PlayerService playerService,
			SessionRegistry sessionRegistry, GhostRecall ghostRecall, CobblemonPartyParser partyParser, AdminService adminService,
			PresenceService presenceService, HubService hubService, Clock clock,
			com.mystaria.phantasmon_backend.battle.format.ShowdownDataSource showdown,
			@Value("${phantasmon.battle.invite-ttl:PT60S}") Duration inviteTtl,
			@Value("${phantasmon.battle.lobby-timer:PT150S}") Duration lobbyTimer) {
		this.battleRepository = battleRepository;
		this.pokemonService = pokemonService;
		this.playerService = playerService;
		this.sessionRegistry = sessionRegistry;
		this.ghostRecall = ghostRecall;
		this.partyParser = partyParser;
		this.clock = clock;
		this.inviteTtl = inviteTtl;
		this.lobbyTimer = lobbyTimer;
		this.showdown = showdown;
		this.adminService = adminService;
		this.presenceService = presenceService;
		this.hubService = hubService;
	}

	private record Invite(UUID uuid, UUID inviterUuid, UUID inviteeUuid, Instant createdAt, BattleTeam inviterTeam) {
	}

	private static final class LiveBattle {
		final UUID uuid;
		final UUID hostUuid;
		final UUID guestUuid;
		/** Admin solo battle: host and guest are the same player, the other side is a mirror played by the host's AI. */
		final boolean solo;
		boolean timerEnabled;
		/** Watching players, in arrival order. */
		final java.util.Set<UUID> spectators = new java.util.LinkedHashSet<>();
		/** Players who see the field without watching (same server group or Global Hub as a player), see {@link #refreshViewers}. */
		final java.util.Set<UUID> viewers = new java.util.LinkedHashSet<>();

		LiveBattle(UUID uuid, UUID hostUuid, UUID guestUuid) {
			this(uuid, hostUuid, guestUuid, false);
		}

		LiveBattle(UUID uuid, UUID hostUuid, UUID guestUuid, boolean solo) {
			this.uuid = uuid;
			this.hostUuid = hostUuid;
			this.guestUuid = guestUuid;
			this.solo = solo;
		}

		UUID other(UUID playerUuid) {
			return hostUuid.equals(playerUuid) ? guestUuid : hostUuid;
		}
	}

	/** One player's side of a lobby: team choice, resolved team (in team order), lead index, ready flag. */
	private static final class LobbySide {
		BattleTeam choice;
		List<PokemonResponse> team;
		int lead;
		boolean ready;
	}

	private static final class Lobby {
		final UUID uuid;
		final UUID inviterUuid;
		final UUID inviteeUuid;
		final LobbySide inviterSide = new LobbySide();
		final LobbySide inviteeSide = new LobbySide();
		/** Lobby timer: null while off. */
		Instant deadline;
		UUID timerBy;
		/** Battle format, picked by either player (TODO-24); "free" = no rule. */
		String formatId = BattleFormats.FREE;

		Lobby(UUID uuid, UUID inviterUuid, UUID inviteeUuid) {
			this.uuid = uuid;
			this.inviterUuid = inviterUuid;
			this.inviteeUuid = inviteeUuid;
		}

		UUID other(UUID playerUuid) {
			return inviterUuid.equals(playerUuid) ? inviteeUuid : inviterUuid;
		}

		LobbySide side(UUID playerUuid) {
			return inviterUuid.equals(playerUuid) ? inviterSide : inviteeSide;
		}
	}

	/** In a battle, a lobby, or watching a battle: no other battle, lobby or spectating at the same time. */
	private boolean isBusy(UUID playerUuid) {
		return battlesByPlayer.containsKey(playerUuid) || lobbiesByPlayer.containsKey(playerUuid)
				|| battlesBySpectator.containsKey(playerUuid);
	}

	// ---- Invitation ----

	/** {@code message}: the {@code BattleInvite} data — target plus the optional team choice ({@link CobblemonPartyParser}). */
	public synchronized void invite(UUID inviterUuid, UUID targetUuid, Map<String, Object> message) {
		BattleTeam inviterTeam;
		try {
			inviterTeam = partyParser.parse(inviterUuid, message);
		} catch (CobblemonPartyParser.InvalidPartyException ex) {
			log.info("Battle invite from {} refused: invalid Cobblemon party ({})", inviterUuid, ex.getMessage());
			sendError(inviterUuid, "ERROR_BATTLE_INVALID_PARTY");
			return;
		}
		if (targetUuid == null) {
			sendError(inviterUuid, "ERROR_WS_MALFORMED_MESSAGE");
		} else if (inviterUuid.equals(targetUuid)) {
			sendError(inviterUuid, "ERROR_BATTLE_SELF");
		} else if (isBusy(inviterUuid)) {
			sendError(inviterUuid, "ERROR_BATTLE_ALREADY_IN_BATTLE");
		} else if (!sessionRegistry.isConnected(targetUuid)) {
			sendError(inviterUuid, "ERROR_BATTLE_PARTNER_UNAVAILABLE");
		} else if (isBusy(targetUuid)) {
			sendError(inviterUuid, "ERROR_BATTLE_PARTNER_BUSY");
		} else {
			invites.values().removeIf(existing -> existing.inviterUuid().equals(inviterUuid) && existing.inviteeUuid().equals(targetUuid));
			Invite invite = new Invite(UUID.randomUUID(), inviterUuid, targetUuid, clock.instant(), inviterTeam);
			invites.put(invite.uuid(), invite);
			log.info("Live battle invite {}: {} invited {}", invite.uuid(), inviterUuid, targetUuid);
			sessionRegistry.send(targetUuid, WsMessage.of("BattleInviteReceived", Map.of(
					"invite_uuid", invite.uuid(), "from_uuid", inviterUuid, "from_name", nameOf(inviterUuid),
					"from_team", inviterTeam.source().name())));
			sessionRegistry.send(inviterUuid, WsMessage.of("BattleInviteSent", Map.of(
					"invite_uuid", invite.uuid(), "to_uuid", targetUuid, "to_name", nameOf(targetUuid))));
		}
	}

	/**
	 * {@code message}: the {@code BattleInviteResponse} data — the invitee's optional initial team choice. An
	 * accepted invitation opens the lobby (the team can still be switched there).
	 */
	public synchronized void respond(UUID inviteeUuid, UUID inviteUuid, boolean accept, Map<String, Object> message) {
		Invite invite = inviteUuid == null ? null : invites.get(inviteUuid);
		if (invite == null || !invite.inviteeUuid().equals(inviteeUuid)
				|| invite.createdAt().plus(inviteTtl).isBefore(clock.instant())) {
			if (invite != null && invite.inviteeUuid().equals(inviteeUuid)) {
				invites.remove(inviteUuid);
			}
			sendError(inviteeUuid, "ERROR_BATTLE_INVITE_NOT_FOUND");
			return;
		}
		invites.remove(inviteUuid);
		UUID inviterUuid = invite.inviterUuid();
		if (!accept) {
			sessionRegistry.send(inviterUuid, WsMessage.of("BattleInviteDeclined", Map.of(
					"invite_uuid", inviteUuid, "by_uuid", inviteeUuid, "by_name", nameOf(inviteeUuid))));
			return;
		}
		if (isBusy(inviteeUuid)) {
			sendError(inviteeUuid, "ERROR_BATTLE_ALREADY_IN_BATTLE");
			return;
		}
		if (!sessionRegistry.isConnected(inviterUuid)) {
			sendError(inviteeUuid, "ERROR_BATTLE_PARTNER_UNAVAILABLE");
			return;
		}
		if (isBusy(inviterUuid)) {
			sendError(inviteeUuid, "ERROR_BATTLE_PARTNER_BUSY");
			return;
		}

		BattleTeam inviteeChoice;
		try {
			inviteeChoice = partyParser.parse(inviteeUuid, message);
		} catch (CobblemonPartyParser.InvalidPartyException ex) {
			log.info("Battle invite answer from {} refused: invalid Cobblemon party ({})", inviteeUuid, ex.getMessage());
			sendError(inviteeUuid, "ERROR_BATTLE_INVALID_PARTY");
			return;
		}
		Lobby lobby = new Lobby(UUID.randomUUID(), inviterUuid, inviteeUuid);
		choose(lobby.inviterSide, inviterUuid, invite.inviterTeam());
		choose(lobby.inviteeSide, inviteeUuid, inviteeChoice);
		lobbiesByPlayer.put(inviterUuid, lobby);
		lobbiesByPlayer.put(inviteeUuid, lobby);
		log.info("Live battle lobby {} opened: {} vs {}", lobby.uuid, inviterUuid, inviteeUuid);
		sendLobby(lobby, inviterUuid);
		sendLobby(lobby, inviteeUuid);
		// Both players' Ghosts go back in from the lobby on (TODO-14); ifNotInBattle keeps them in.
		ghostRecall.recall(inviterUuid, "battle lobby");
		ghostRecall.recall(inviteeUuid, "battle lobby");
	}

	// ---- Lobby ----

	/** Sets a side's team (resolving Ghosts to the active team, in team order); the lead goes back to the first. */
	private void choose(LobbySide side, UUID playerUuid, BattleTeam choice) {
		side.choice = choice;
		side.team = resolve(playerUuid, choice);
		side.lead = 0;
	}

	private List<PokemonResponse> resolve(UUID playerUuid, BattleTeam choice) {
		List<PokemonResponse> team = new ArrayList<>(choice.isGhost() ? pokemonService.activeTeam(playerUuid) : choice.party());
		team.sort(Comparator.comparing(PokemonResponse::teamSlot, Comparator.nullsLast(Comparator.naturalOrder())));
		return team;
	}

	/** Switch between Ghosts and a copy of the Cobblemon party. Unreadies the opponent: what they saw changed. */
	public synchronized void setLobbyTeam(UUID playerUuid, UUID lobbyUuid, Map<String, Object> message) {
		Lobby lobby = lobbyOf(playerUuid, lobbyUuid);
		if (lobby == null) {
			return;
		}
		LobbySide side = lobby.side(playerUuid);
		if (side.ready) {
			sendError(playerUuid, "ERROR_BATTLE_LOBBY_LOCKED");
			return;
		}
		BattleTeam choice;
		try {
			choice = partyParser.parse(playerUuid, message);
		} catch (CobblemonPartyParser.InvalidPartyException ex) {
			log.info("Battle lobby team from {} refused: invalid Cobblemon party ({})", playerUuid, ex.getMessage());
			sendError(playerUuid, "ERROR_BATTLE_INVALID_PARTY");
			return;
		}
		choose(side, playerUuid, choice);
		lobby.side(lobby.other(playerUuid)).ready = false;
		sendLobby(lobby, playerUuid);
		sendLobby(lobby, lobby.other(playerUuid));
	}

	/**
	 * Battle format (TODO-24): either player picks it, for both. Everyone is unreadied — what the teams may contain
	 * changed. Unknown id: {@code ERROR_BATTLE_UNKNOWN_FORMAT}.
	 */
	public synchronized void setLobbyFormat(UUID playerUuid, UUID lobbyUuid, String formatId) {
		Lobby lobby = lobbyOf(playerUuid, lobbyUuid);
		if (lobby == null) {
			return;
		}
		if (showdown.formats().get(formatId) == null) {
			sendError(playerUuid, "ERROR_BATTLE_UNKNOWN_FORMAT");
			return;
		}
		if (formatId.equals(lobby.formatId)) {
			return;
		}
		lobby.formatId = formatId;
		lobby.inviterSide.ready = false;
		lobby.inviteeSide.ready = false;
		log.info("Live battle lobby {}: format {} picked by {}", lobby.uuid, formatId, playerUuid);
		sendLobby(lobby, playerUuid);
		sendLobby(lobby, lobby.other(playerUuid));
	}

	private BattleFormats.Format format(Lobby lobby) {
		BattleFormats.Format format = showdown.formats().get(lobby.formatId);
		return format != null ? format : showdown.formats().get(BattleFormats.FREE);
	}

	/** Per member of {@code team}: the rules of the lobby's format it breaks (empty = fine). */
	private List<List<TeamValidator.Issue>> issues(Lobby lobby, List<PokemonResponse> team) {
		List<TeamValidator.Member> members = team.stream().map(LiveBattleService::member).toList();
		return showdown.validator().validate(format(lobby), members);
	}

	private static boolean breaksRules(List<List<TeamValidator.Issue>> issues) {
		return issues.stream().anyMatch(list -> !list.isEmpty());
	}

	private static TeamValidator.Member member(PokemonResponse pokemon) {
		Map<String, Object> data = pokemon.data() == null ? Map.of() : pokemon.data();
		List<String> moves = data.get("moves") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
		Object item = data.get("held_item");
		Object nickname = data.get("nickname");
		return new TeamValidator.Member(pokemon.species(), pokemon.form(), pokemon.ability(),
				item == null ? null : item.toString(), moves, nickname == null ? null : nickname.toString());
	}

	/** Picks the lead. Only the player is told: the opponent must not learn it, nor even that it changed. */
	public synchronized void setLobbyLead(UUID playerUuid, UUID lobbyUuid, Integer index) {
		Lobby lobby = lobbyOf(playerUuid, lobbyUuid);
		if (lobby == null) {
			return;
		}
		LobbySide side = lobby.side(playerUuid);
		if (side.ready) {
			sendError(playerUuid, "ERROR_BATTLE_LOBBY_LOCKED");
		} else if (index == null || index < 0 || index >= side.team.size()) {
			sendError(playerUuid, "ERROR_WS_MALFORMED_MESSAGE");
		} else {
			side.lead = index;
			sendLobby(lobby, playerUuid);
		}
	}

	/** Ready (team and lead locked) or not. Both ready: the battle starts. */
	public synchronized void setLobbyReady(UUID playerUuid, UUID lobbyUuid, boolean ready) {
		Lobby lobby = lobbyOf(playerUuid, lobbyUuid);
		if (lobby == null) {
			return;
		}
		LobbySide side = lobby.side(playerUuid);
		if (ready && side.team.isEmpty()) {
			sendError(playerUuid, "ERROR_BATTLE_EMPTY_TEAM");
			return;
		}
		if (ready && breaksRules(issues(lobby, side.team))) {
			sendError(playerUuid, "ERROR_BATTLE_TEAM_NOT_ALLOWED");
			return;
		}
		side.ready = ready;
		if (lobby.inviterSide.ready && lobby.inviteeSide.ready) {
			startBattle(lobby);
		} else {
			sendLobby(lobby, playerUuid);
			sendLobby(lobby, lobby.other(playerUuid));
		}
	}

	/** Either player, once: the lobby timer for both; it carries over as the battle's turn timer. */
	public synchronized void enableLobbyTimer(UUID playerUuid, UUID lobbyUuid) {
		Lobby lobby = lobbyOf(playerUuid, lobbyUuid);
		if (lobby == null || lobby.deadline != null) {
			return;
		}
		lobby.deadline = clock.instant().plus(lobbyTimer);
		lobby.timerBy = playerUuid;
		sendLobby(lobby, playerUuid);
		sendLobby(lobby, lobby.other(playerUuid));
	}

	public synchronized void leaveLobby(UUID playerUuid, UUID lobbyUuid) {
		Lobby lobby = lobbyOf(playerUuid, lobbyUuid);
		if (lobby != null) {
			cancelLobby(lobby, "LEFT", playerUuid);
		}
	}

	@Scheduled(fixedRate = 1000)
	public void tickLobbies() {
		expireLobbies(clock.instant());
	}

	/** Lobby timer out: whoever is not ready leads with their first Pokémon and is readied; the battle starts. */
	public synchronized void expireLobbies(Instant now) {
		for (Lobby lobby : new LinkedHashSet<>(lobbiesByPlayer.values())) {
			if (lobby.deadline == null || lobby.deadline.isAfter(now)) {
				continue;
			}
			if (lobby.inviterSide.team.isEmpty() || lobby.inviteeSide.team.isEmpty()) {
				cancelLobby(lobby, "EMPTY_TEAM", null);
				continue;
			}
			if (breaksRules(issues(lobby, lobby.inviterSide.team)) || breaksRules(issues(lobby, lobby.inviteeSide.team))) {
				cancelLobby(lobby, "TEAM_NOT_ALLOWED", null);
				continue;
			}
			for (LobbySide side : List.of(lobby.inviterSide, lobby.inviteeSide)) {
				if (!side.ready) {
					side.lead = 0;
					side.ready = true;
				}
			}
			startBattle(lobby);
		}
	}

	private void cancelLobby(Lobby lobby, String reason, UUID byUuid) {
		lobbiesByPlayer.remove(lobby.inviterUuid);
		lobbiesByPlayer.remove(lobby.inviteeUuid);
		log.info("Live battle lobby {} cancelled ({})", lobby.uuid, reason);
		Map<String, Object> data = new HashMap<>();
		data.put("lobby_uuid", lobby.uuid);
		data.put("reason", reason);
		data.put("by_name", byUuid == null ? null : nameOf(byUuid));
		sessionRegistry.send(lobby.inviterUuid, WsMessage.of("BattleLobbyCancelled", data));
		sessionRegistry.send(lobby.inviteeUuid, WsMessage.of("BattleLobbyCancelled", data));
	}

	/**
	 * The lobby as {@code viewerUuid} sees it: their own team in full with their lead, the opponent's as a preview
	 * (species, form, shininess, gender — what the model shows) and whether they are ready; never their lead.
	 */
	private void sendLobby(Lobby lobby, UUID viewerUuid) {
		UUID opponentUuid = lobby.other(viewerUuid);
		LobbySide own = lobby.side(viewerUuid);
		LobbySide opponent = lobby.side(opponentUuid);
		Map<String, Object> data = new HashMap<>();
		data.put("lobby_uuid", lobby.uuid);
		data.put("opponent_uuid", opponentUuid);
		data.put("opponent_name", nameOf(opponentUuid));
		data.put("own_team_source", own.choice.source().name());
		data.put("own_team", own.team);
		data.put("own_lead", own.lead);
		data.put("own_ready", own.ready);
		data.put("opponent_team_source", opponent.choice.source().name());
		data.put("opponent_team", opponent.team.stream().map(LiveBattleService::preview).toList());
		data.put("opponent_ready", opponent.ready);
		data.put("timer_total_seconds", lobbyTimer.toSeconds());
		data.put("timer_seconds_left", lobby.deadline == null ? null
				: Math.max(0, Duration.between(clock.instant(), lobby.deadline).plusMillis(999).toSeconds()));
		data.put("timer_by_name", lobby.timerBy == null ? null : nameOf(lobby.timerBy));
		// Format (TODO-24): the offered list, the picked one, what breaks it — in detail for one's own team, only a
		// red circle for the opponent's (its items and moves stay hidden).
		data.put("format_id", lobby.formatId);
		data.put("formats", showdown.formats().offered().stream()
				.map(format -> Map.of("id", format.id(), "name", format.name())).toList());
		data.put("own_team_issues", issues(lobby, own.team).stream()
				.map(list -> list.stream().map(issue -> Map.of("code", issue.code(), "subject", issue.subject())).toList())
				.toList());
		data.put("opponent_team_flags", issues(lobby, opponent.team).stream().map(list -> !list.isEmpty()).toList());
		sessionRegistry.send(viewerUuid, WsMessage.of("BattleLobbyUpdated", data));
	}

	private static Map<String, Object> preview(PokemonResponse pokemon) {
		Map<String, Object> preview = new HashMap<>();
		preview.put("species", pokemon.species());
		preview.put("form", pokemon.form());
		preview.put("is_shiny", pokemon.isShiny());
		preview.put("gender", pokemon.data() == null ? null : pokemon.data().get("gender"));
		return preview;
	}

	private Lobby lobbyOf(UUID playerUuid, UUID lobbyUuid) {
		Lobby lobby = lobbiesByPlayer.get(playerUuid);
		if (lobby == null || lobbyUuid == null || !lobby.uuid.equals(lobbyUuid)) {
			sendError(playerUuid, "ERROR_BATTLE_LOBBY_NOT_FOUND");
			return null;
		}
		return lobby;
	}

	/** A side's final team: Ghosts re-read (the team may have changed meanwhile), the lead moved first, slots renumbered. */
	private List<PokemonResponse> battleTeam(UUID playerUuid, LobbySide side) {
		UUID leadUuid = side.team.isEmpty() ? null : side.team.get(side.lead).uuid();
		List<PokemonResponse> team = side.choice.isGhost() ? resolve(playerUuid, side.choice) : new ArrayList<>(side.team);
		team.stream().filter(pokemon -> pokemon.uuid().equals(leadUuid)).findFirst().ifPresent(lead -> {
			team.remove(lead);
			team.add(0, lead);
		});
		List<PokemonResponse> ordered = new ArrayList<>();
		for (int i = 0; i < team.size(); i++) {
			PokemonResponse p = team.get(i);
			ordered.add(new PokemonResponse(p.uuid(), p.ownerUuid(), p.species(), p.form(), p.level(), p.nature(), p.ability(),
					p.isShiny(), p.boxId(), p.boxSlot(), i + 1, p.cobblemonDataVersion(), p.data()));
		}
		return ordered;
	}

	// ---- Battle start ----

	private void startBattle(Lobby lobby) {
		UUID inviterUuid = lobby.inviterUuid;
		UUID inviteeUuid = lobby.inviteeUuid;
		List<PokemonResponse> inviterTeam = battleTeam(inviterUuid, lobby.inviterSide);
		List<PokemonResponse> inviteeTeam = battleTeam(inviteeUuid, lobby.inviteeSide);
		if (inviterTeam.isEmpty() || inviteeTeam.isEmpty()) {
			cancelLobby(lobby, "EMPTY_TEAM", null);
			return;
		}
		// Ghosts are re-read here: a team changed since "ready" is checked again.
		if (breaksRules(issues(lobby, inviterTeam)) || breaksRules(issues(lobby, inviteeTeam))) {
			cancelLobby(lobby, "TEAM_NOT_ALLOWED", null);
			return;
		}
		BattleFormats.Format format = format(lobby);
		if (format.pickedTeamSize() > 0) {
			// 1v1: only the lead (first after battleTeam's reordering) goes into the battle.
			inviterTeam = inviterTeam.subList(0, Math.min(format.pickedTeamSize(), inviterTeam.size()));
			inviteeTeam = inviteeTeam.subList(0, Math.min(format.pickedTeamSize(), inviteeTeam.size()));
		}
		Map<String, Object> formatData = Map.of("id", format.id(), "name", format.name(),
				"battle_rules", format.battleRules(), "adjust_level", format.adjustLevel());
		lobbiesByPlayer.remove(inviterUuid);
		lobbiesByPlayer.remove(inviteeUuid);

		UUID hostUuid = nextHost(inviterUuid, inviteeUuid);
		UUID guestUuid = hostUuid.equals(inviterUuid) ? inviteeUuid : inviterUuid;
		List<PokemonResponse> hostTeam = hostUuid.equals(inviterUuid) ? inviterTeam : inviteeTeam;
		List<PokemonResponse> guestTeam = hostUuid.equals(inviterUuid) ? inviteeTeam : inviterTeam;
		String hostSource = lobby.side(hostUuid).choice.source().name();
		String guestSource = lobby.side(guestUuid).choice.source().name();

		BattleSession session = BattleSession.hosted(UUID.randomUUID(), hostUuid, guestUuid,
				hostTeam.stream().map(PokemonResponse::uuid).toList(), guestTeam.stream().map(PokemonResponse::uuid).toList());
		battleRepository.save(session);
		LiveBattle battle = new LiveBattle(session.getUuid(), hostUuid, guestUuid);
		battlesByPlayer.put(hostUuid, battle);
		battlesByPlayer.put(guestUuid, battle);
		log.info("Live battle {} started from lobby {}: host {} vs guest {}", battle.uuid, lobby.uuid, hostUuid, guestUuid);

		// TODO-26: the intro cinematic is drawn here, so that both players watch the same one.
		int intro = java.util.concurrent.ThreadLocalRandom.current().nextInt(INTRO_COUNT);
		Map<String, Object> hostView = new HashMap<>(Map.of(
				"battle_uuid", battle.uuid, "role", "HOST",
				"opponent_uuid", guestUuid, "opponent_name", nameOf(guestUuid),
				"own_team", hostTeam, "opponent_team", guestTeam,
				"own_team_source", hostSource, "opponent_team_source", guestSource, "format", formatData));
		hostView.put("intro", intro);
		sessionRegistry.send(hostUuid, WsMessage.of("BattleSessionStarted", hostView));
		sessionRegistry.send(guestUuid, WsMessage.of("BattleSessionStarted", Map.of(
				"battle_uuid", battle.uuid, "role", "GUEST",
				"opponent_uuid", hostUuid, "opponent_name", nameOf(hostUuid),
				"own_team", guestTeam, "own_team_source", guestSource, "opponent_team_source", hostSource,
				"format", formatData, "intro", intro)));
		ghostRecall.recall(hostUuid, "battle started");
		ghostRecall.recall(guestUuid, "battle started");
		if (lobby.timerBy != null) {
			enableTimer(battle, lobby.timerBy);
		}
		refreshViewers(battle);
	}

	// ---- Admin solo battle ----

	/**
	 * {@code BattleSoloStart} (admin, Adrien 2026-10-07): a live battle against a mirror of the admin's own Ghost team,
	 * played by Cobblemon's AI on the admin's client (the host). No lobby, the "Libre" format, never stored
	 * ({@code battle_sessions} forbids a player against themself) — but a live battle all the same, so spectators can
	 * watch it: a way to test spectating, battle visuals and set pieces alone.
	 */
	public synchronized void startSolo(UUID adminUuid) {
		if (!adminService.isAdmin(adminUuid)) {
			sendError(adminUuid, "ERROR_ADMIN_REQUIRED");
			return;
		}
		if (isBusy(adminUuid)) {
			sendError(adminUuid, "ERROR_BATTLE_ALREADY_IN_BATTLE");
			return;
		}
		List<PokemonResponse> team = resolve(adminUuid, BattleTeam.GHOST);
		if (team.isEmpty()) {
			sendError(adminUuid, "ERROR_BATTLE_EMPTY_TEAM");
			return;
		}
		UUID mirrorUuid = mirrorOf(adminUuid);
		LiveBattle battle = new LiveBattle(UUID.randomUUID(), adminUuid, adminUuid, true);
		battlesByPlayer.put(adminUuid, battle);
		log.info("Admin solo battle {} started by {}", battle.uuid, adminUuid);
		BattleFormats.Format free = showdown.formats().get(BattleFormats.FREE);
		Map<String, Object> view = new HashMap<>(Map.of(
				"battle_uuid", battle.uuid, "role", "HOST", "solo", true,
				"opponent_uuid", mirrorUuid, "opponent_name", nameOf(adminUuid) + " (miroir)",
				"own_team", team, "opponent_team", team,
				"own_team_source", BattleTeam.GHOST.source().name(), "opponent_team_source", BattleTeam.GHOST.source().name(),
				"format", Map.of("id", free.id(), "name", free.name(), "battle_rules", free.battleRules(),
						"adjust_level", free.adjustLevel())));
		view.put("intro", java.util.concurrent.ThreadLocalRandom.current().nextInt(INTRO_COUNT));
		sessionRegistry.send(adminUuid, WsMessage.of("BattleSessionStarted", view));
		ghostRecall.recall(adminUuid, "battle started");
		refreshViewers(battle);
	}

	/** The mirror side of an admin's solo battle: a stable uuid of its own, never a real player's. */
	static UUID mirrorOf(UUID adminUuid) {
		return UUID.nameUUIDFromBytes(("phantasmon-mirror:" + adminUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Runs {@code action} only if the player is neither in a live battle nor in its lobby, under the same lock as a battle start, so a
	 * Ghost can never be sent out between the start's recall and the battle being registered. Returns whether it ran.
	 */
	public synchronized boolean ifNotInBattle(UUID playerUuid, Runnable action) {
		if (isBusy(playerUuid)) {
			return false;
		}
		action.run();
		return true;
	}

	/** Alternation: whoever did not host the pair's previous live battle; the inviter for their very first one. */
	private UUID nextHost(UUID inviterUuid, UUID inviteeUuid) {
		List<BattleSession> history = battleRepository.findHostedBetween(inviterUuid, inviteeUuid);
		if (history.isEmpty()) {
			return inviterUuid;
		}
		UUID previousHost = history.get(0).getHostUuid();
		return previousHost.equals(inviterUuid) ? inviteeUuid : inviterUuid;
	}

	// ---- Relay ----

	/** Host → guest: one encoded Cobblemon battle packet, forwarded as is. */
	public synchronized void relayPacket(UUID senderUuid, UUID battleUuid, Map<String, Object> data) {
		LiveBattle battle = battleOf(senderUuid, battleUuid);
		if (battle == null) {
			return;
		}
		if (!battle.hostUuid.equals(senderUuid)) {
			sendError(senderUuid, "ERROR_BATTLE_NOT_HOST");
			return;
		}
		sessionRegistry.send(battle.guestUuid, WsMessage.of("BattlePacket", data));
	}

	// ---- Spectators ----

	/**
	 * {@code BattleSpectate}: watch the battle {@code targetUuid} plays in, like Cobblemon's spectate. The spectator
	 * is told who plays; both players are told who watches — the host then sends this spectator the battle so far
	 * ({@code BattleSpectatorPacket} with {@code spectator_uuid}) and streams what its engine shows spectators.
	 */
	public synchronized void spectate(UUID spectatorUuid, UUID targetUuid) {
		if (targetUuid == null) {
			sendError(spectatorUuid, "ERROR_WS_MALFORMED_MESSAGE");
			return;
		}
		if (spectatorUuid.equals(targetUuid)) {
			sendError(spectatorUuid, "ERROR_BATTLE_SELF");
			return;
		}
		if (isBusy(spectatorUuid)) {
			sendError(spectatorUuid, "ERROR_BATTLE_ALREADY_IN_BATTLE");
			return;
		}
		LiveBattle battle = battlesByPlayer.get(targetUuid);
		if (battle == null) {
			sendError(spectatorUuid, "ERROR_BATTLE_SPECTATE_NOT_BATTLING");
			return;
		}
		if (battle.viewers.remove(spectatorUuid)) {
			// Their spectator screen rebuilds the scene: the field view goes first.
			removeViewer(battle, spectatorUuid, "SPECTATING");
		}
		battle.spectators.add(spectatorUuid);
		battlesBySpectator.put(spectatorUuid, battle);
		log.info("Player {} watches live battle {}", spectatorUuid, battle.uuid);
		sessionRegistry.send(spectatorUuid, WsMessage.of("BattleSpectateStarted", Map.of("battle_uuid", battle.uuid,
				"host_uuid", battle.hostUuid, "host_name", nameOf(battle.hostUuid),
				"guest_uuid", battle.guestUuid, "guest_name", nameOf(battle.guestUuid))));
		WsMessage joined = WsMessage.of("BattleSpectatorJoined", Map.of("battle_uuid", battle.uuid,
				"spectator_uuid", spectatorUuid, "spectator_name", nameOf(spectatorUuid)));
		sessionRegistry.send(battle.hostUuid, joined);
		sessionRegistry.send(battle.guestUuid, joined);
	}

	/**
	 * Host → spectators: one encoded packet of the engine's spectator stream (public information only — never the
	 * guest's relay, which carries the guest's own team and requests). With {@code spectator_uuid}, for that
	 * spectator alone (their catch-up), else for every spectator.
	 */
	public synchronized void relaySpectatorPacket(UUID senderUuid, UUID battleUuid, Map<String, Object> data) {
		LiveBattle battle = battleOf(senderUuid, battleUuid);
		if (battle == null) {
			return;
		}
		if (!battle.hostUuid.equals(senderUuid)) {
			sendError(senderUuid, "ERROR_BATTLE_NOT_HOST");
			return;
		}
		Map<String, Object> packet = new HashMap<>(data);
		Object target = packet.remove("spectator_uuid");
		WsMessage message = WsMessage.of("BattleSpectatorPacket", packet);
		WsMessage field = WsMessage.of("BattleFieldPacket", packet);
		if (target == null) {
			refreshViewers(battle);
			battle.spectators.forEach(spectator -> sessionRegistry.send(spectator, message));
			battle.viewers.forEach(viewer -> sessionRegistry.send(viewer, field));
			return;
		}
		battle.spectators.stream().filter(spectator -> spectator.toString().equals(target.toString())).findFirst()
				.ifPresent(spectator -> sessionRegistry.send(spectator, message));
		battle.viewers.stream().filter(viewer -> viewer.toString().equals(target.toString())).findFirst()
				.ifPresent(viewer -> sessionRegistry.send(viewer, field));
	}

	// ---- Field viewers ----

	@Scheduled(fixedRate = 1000)
	public void tickViewers() {
		refreshAllViewers();
	}

	public synchronized void refreshAllViewers() {
		new LinkedHashSet<>(battlesByPlayer.values()).forEach(this::refreshViewers);
	}

	/**
	 * Like Cobblemon, where everyone around sees the Pokémon of a battle (Adrien 2026-10-07): the players of either
	 * player's server group ({@code server_fingerprint + dimension}) and, when either player is in a hub, every member
	 * of that hub see the field — the Pokémon, send-outs, recalls, move animations, gimmicks — without the battle
	 * screen. Neither player nor a spectator is a viewer. Newcomers are announced to the host
	 * ({@code BattleFieldViewerJoined}), which sends them the field as it stands ({@code BattleSpectatorPacket} with
	 * their uuid) then its spectator stream, forwarded as {@code BattleFieldPacket}; those who leave get
	 * {@code BattleFieldEnded}. Re-evaluated every second and before each streamed packet.
	 */
	private void refreshViewers(LiveBattle battle) {
		java.util.Set<UUID> wanted = new LinkedHashSet<>();
		wanted.addAll(presenceService.groupMembers(battle.hostUuid));
		wanted.addAll(presenceService.groupMembers(battle.guestUuid));
		// The hub each player is in (D-35: a hub's members see each other, not those of another hub).
		wanted.addAll(hubService.sameHubMembers(battle.hostUuid));
		wanted.addAll(hubService.sameHubMembers(battle.guestUuid));
		wanted.remove(battle.hostUuid);
		wanted.remove(battle.guestUuid);
		wanted.removeAll(battle.spectators);
		for (UUID viewer : List.copyOf(battle.viewers)) {
			if (!wanted.contains(viewer)) {
				battle.viewers.remove(viewer);
				removeViewer(battle, viewer, "OUT_OF_RANGE");
			}
		}
		for (UUID viewer : wanted) {
			if (battle.viewers.add(viewer)) {
				sessionRegistry.send(battle.hostUuid, WsMessage.of("BattleFieldViewerJoined",
						Map.of("battle_uuid", battle.uuid, "viewer_uuid", viewer)));
			}
		}
	}

	/**
	 * {@code viewer} no longer sees the field ({@code reason}: {@code OUT_OF_RANGE}, {@code SPECTATING} — their battle
	 * screen takes over); they clear it, the host stops counting them.
	 */
	private void removeViewer(LiveBattle battle, UUID viewer, String reason) {
		sessionRegistry.send(viewer, WsMessage.of("BattleFieldEnded", Map.of("battle_uuid", battle.uuid, "reason", reason)));
		sessionRegistry.send(battle.hostUuid, WsMessage.of("BattleFieldViewerLeft",
				Map.of("battle_uuid", battle.uuid, "viewer_uuid", viewer)));
	}

	/** {@code BattleSpectateLeave}: stops watching (Cobblemon's back button on the battle screen). */
	public synchronized void leaveSpectating(UUID spectatorUuid, UUID battleUuid) {
		LiveBattle battle = battlesBySpectator.get(spectatorUuid);
		if (battle == null || battleUuid == null || !battle.uuid.equals(battleUuid)) {
			sendError(spectatorUuid, "ERROR_BATTLE_NOT_IN_BATTLE");
			return;
		}
		stopSpectating(spectatorUuid, battle);
		sessionRegistry.send(spectatorUuid, WsMessage.of("BattleSpectateEnded", Map.of("battle_uuid", battle.uuid, "reason", "LEFT")));
	}

	private void stopSpectating(UUID spectatorUuid, LiveBattle battle) {
		battle.spectators.remove(spectatorUuid);
		battlesBySpectator.remove(spectatorUuid);
		log.info("Player {} stopped watching live battle {}", spectatorUuid, battle.uuid);
		WsMessage left = WsMessage.of("BattleSpectatorLeft", Map.of("battle_uuid", battle.uuid, "spectator_uuid", spectatorUuid));
		sessionRegistry.send(battle.hostUuid, left);
		sessionRegistry.send(battle.guestUuid, left);
	}

	/** Guest → host: the guest's encoded choice, forwarded as is. */
	public synchronized void relayChoice(UUID senderUuid, UUID battleUuid, Map<String, Object> data) {
		LiveBattle battle = battleOf(senderUuid, battleUuid);
		if (battle == null) {
			return;
		}
		if (!battle.guestUuid.equals(senderUuid)) {
			sendError(senderUuid, "ERROR_BATTLE_NOT_GUEST");
			return;
		}
		sessionRegistry.send(battle.hostUuid, WsMessage.of("BattleChoice", data));
	}

	/** Either player, once: enforced by the host's engine from then on, for both sides. */
	public synchronized void enableTimer(UUID senderUuid, UUID battleUuid) {
		LiveBattle battle = battleOf(senderUuid, battleUuid);
		if (battle == null || battle.timerEnabled) {
			return;
		}
		enableTimer(battle, senderUuid);
	}

	private void enableTimer(LiveBattle battle, UUID byUuid) {
		battle.timerEnabled = true;
		WsMessage enabled = WsMessage.of("BattleTimerEnabled", Map.of(
				"battle_uuid", battle.uuid, "by_uuid", byUuid, "by_name", nameOf(byUuid), "seconds", TIMER_SECONDS));
		sessionRegistry.send(battle.hostUuid, enabled);
		sessionRegistry.send(battle.guestUuid, enabled);
	}

	// ---- End ----

	/** The host's engine finished the battle. {@code winnerUuid} null = draw. */
	public synchronized void reportResult(UUID senderUuid, UUID battleUuid, UUID winnerUuid) {
		LiveBattle battle = battleOf(senderUuid, battleUuid);
		if (battle == null) {
			return;
		}
		if (!battle.hostUuid.equals(senderUuid)) {
			sendError(senderUuid, "ERROR_BATTLE_NOT_HOST");
			return;
		}
		boolean mirrorWon = battle.solo && mirrorOf(battle.hostUuid).equals(winnerUuid);
		if (winnerUuid != null && !mirrorWon && !winnerUuid.equals(battle.hostUuid) && !winnerUuid.equals(battle.guestUuid)) {
			sendError(senderUuid, "ERROR_BATTLE_INVALID_RESULT");
			return;
		}
		finish(battle, winnerUuid, "FINISHED", BattleStatus.FINISHED);
	}

	/** A player quits the battle: forfeit, the other one wins. */
	public synchronized void leave(UUID senderUuid, UUID battleUuid) {
		LiveBattle battle = battleOf(senderUuid, battleUuid);
		if (battle != null) {
			finish(battle, battle.other(senderUuid), "FORFEIT", BattleStatus.FINISHED);
		}
	}

	/**
	 * Backend stopping (CAD Partie 1 §44): every live battle ends as a draw — no winner, {@code BACKEND_LOST} — and
	 * both players are told. {@link ContextClosedEvent} is published before the web server closes the WebSocket
	 * connections, so the {@code BattleEnded} still gets through (otherwise the closing connections would end the
	 * battles as mere disconnections).
	 */
	@EventListener(ContextClosedEvent.class)
	public void onBackendStopping() {
		endAllAsDraw("BACKEND_LOST");
	}

	public synchronized void endAllAsDraw(String reason) {
		for (Lobby lobby : new LinkedHashSet<>(lobbiesByPlayer.values())) {
			cancelLobby(lobby, reason, null);
		}
		for (LiveBattle battle : new java.util.LinkedHashSet<>(battlesByPlayer.values())) {
			finish(battle, null, reason, BattleStatus.FINISHED);
		}
	}

	/**
	 * Startup (CAD Partie 1 §44): a backend that crashed could not close its battles — they only live in memory — so
	 * their sessions were left {@code ACTIVE} for good. Each one not actually running here is recorded as a draw
	 * ({@code BACKEND_LOST}). Ghost Pokémon are never touched by a battle, there is nothing else to restore.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public synchronized void closeOrphanedBattles() {
		java.util.Set<UUID> running = new java.util.HashSet<>();
		battlesByPlayer.values().forEach(battle -> running.add(battle.uuid));
		List<BattleSession> orphans = battleRepository.findByStatus(BattleStatus.ACTIVE).stream()
				.filter(session -> !running.contains(session.getUuid()))
				.toList();
		for (BattleSession session : orphans) {
			Map<String, Object> result = new HashMap<>();
			result.put("winner_uuid", null);
			result.put("reason", "BACKEND_LOST");
			session.setStatus(BattleStatus.FINISHED);
			session.setResult(result);
			session.setFinishedAt(Instant.now());
		}
		battleRepository.saveAll(orphans);
		if (!orphans.isEmpty()) {
			log.info("Closed {} battle session(s) left active by a previous backend run as draws (BACKEND_LOST)", orphans.size());
		}
	}

	/**
	 * Admin (TODO-25): ends the battle {@code playerUuid} is in as a draw ({@code ADMIN_STOPPED}, no winner), or
	 * cancels their lobby. Returns {@code "BATTLE"}, {@code "LOBBY"}, or null if there was nothing to stop.
	 */
	public synchronized String adminStop(UUID playerUuid) {
		LiveBattle battle = battlesByPlayer.get(playerUuid);
		if (battle != null) {
			finish(battle, null, "ADMIN_STOPPED", BattleStatus.FINISHED);
			return "BATTLE";
		}
		Lobby lobby = lobbiesByPlayer.get(playerUuid);
		if (lobby != null) {
			cancelLobby(lobby, "ADMIN_STOPPED", null);
			return "LOBBY";
		}
		return null;
	}

	/** WebSocket closed: the lobby is cancelled; a battle can't go on — a draw (CAD Partie 1 §44). */
	public synchronized void onDisconnect(UUID playerUuid) {
		invites.values().removeIf(invite -> invite.inviterUuid().equals(playerUuid) || invite.inviteeUuid().equals(playerUuid));
		LiveBattle watched = battlesBySpectator.get(playerUuid);
		if (watched != null) {
			stopSpectating(playerUuid, watched);
		}
		Lobby lobby = lobbiesByPlayer.get(playerUuid);
		if (lobby != null) {
			cancelLobby(lobby, "PARTNER_DISCONNECTED", playerUuid);
		}
		LiveBattle battle = battlesByPlayer.get(playerUuid);
		if (battle != null) {
			finish(battle, null, "PARTNER_DISCONNECTED", BattleStatus.ABORTED);
		}
	}

	private void finish(LiveBattle battle, UUID winnerUuid, String reason, BattleStatus status) {
		battlesByPlayer.remove(battle.hostUuid);
		battlesByPlayer.remove(battle.guestUuid);
		battleRepository.findById(battle.uuid).ifPresent(session -> {
			Map<String, Object> result = new HashMap<>();
			result.put("winner_uuid", winnerUuid == null ? null : winnerUuid.toString());
			result.put("reason", reason);
			session.setStatus(status);
			session.setResult(result);
			session.setFinishedAt(Instant.now());
			battleRepository.save(session);
		});
		log.info("Live battle {} ended ({}), winner {}", battle.uuid, reason, winnerUuid);
		Map<String, Object> data = new HashMap<>();
		data.put("battle_uuid", battle.uuid);
		data.put("winner_uuid", winnerUuid);
		data.put("reason", reason);
		sessionRegistry.send(battle.hostUuid, WsMessage.of("BattleEnded", data));
		if (!battle.guestUuid.equals(battle.hostUuid)) {
			sessionRegistry.send(battle.guestUuid, WsMessage.of("BattleEnded", data));
		}
		WsMessage spectateEnded = WsMessage.of("BattleSpectateEnded", data);
		for (UUID spectator : battle.spectators) {
			battlesBySpectator.remove(spectator);
			sessionRegistry.send(spectator, spectateEnded);
		}
		battle.spectators.clear();
		WsMessage fieldEnded = WsMessage.of("BattleFieldEnded", Map.of("battle_uuid", battle.uuid, "reason", reason));
		battle.viewers.forEach(viewer -> sessionRegistry.send(viewer, fieldEnded));
		battle.viewers.clear();
	}

	private LiveBattle battleOf(UUID playerUuid, UUID battleUuid) {
		LiveBattle battle = battlesByPlayer.get(playerUuid);
		if (battle == null || battleUuid == null || !battle.uuid.equals(battleUuid)) {
			sendError(playerUuid, "ERROR_BATTLE_NOT_IN_BATTLE");
			return null;
		}
		return battle;
	}

	private void sendError(UUID playerUuid, String errorCode) {
		sessionRegistry.send(playerUuid, WsMessage.of("BattleSessionError", Map.of("error_code", errorCode)));
	}

	private String nameOf(UUID playerUuid) {
		return playerService.findById(playerUuid).map(Player::getLastUsername).orElse("?");
	}
}
