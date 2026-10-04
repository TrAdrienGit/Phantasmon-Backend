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

import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerService;
import com.mystaria.phantasmon_backend.pokemon.PokemonResponse;
import com.mystaria.phantasmon_backend.pokemon.PokemonService;
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

	static final int TIMER_SECONDS = 90;

	private final Map<UUID, LiveBattle> battlesByPlayer = new HashMap<>();
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

	public LiveBattleService(BattleRepository battleRepository, PokemonService pokemonService, PlayerService playerService,
			SessionRegistry sessionRegistry, GhostRecall ghostRecall, CobblemonPartyParser partyParser, Clock clock,
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
	}

	private record Invite(UUID uuid, UUID inviterUuid, UUID inviteeUuid, Instant createdAt, BattleTeam inviterTeam) {
	}

	private static final class LiveBattle {
		final UUID uuid;
		final UUID hostUuid;
		final UUID guestUuid;
		boolean timerEnabled;

		LiveBattle(UUID uuid, UUID hostUuid, UUID guestUuid) {
			this.uuid = uuid;
			this.hostUuid = hostUuid;
			this.guestUuid = guestUuid;
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

	private boolean isBusy(UUID playerUuid) {
		return battlesByPlayer.containsKey(playerUuid) || lobbiesByPlayer.containsKey(playerUuid);
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

		sessionRegistry.send(hostUuid, WsMessage.of("BattleSessionStarted", Map.of(
				"battle_uuid", battle.uuid, "role", "HOST",
				"opponent_uuid", guestUuid, "opponent_name", nameOf(guestUuid),
				"own_team", hostTeam, "opponent_team", guestTeam,
				"own_team_source", hostSource, "opponent_team_source", guestSource)));
		sessionRegistry.send(guestUuid, WsMessage.of("BattleSessionStarted", Map.of(
				"battle_uuid", battle.uuid, "role", "GUEST",
				"opponent_uuid", hostUuid, "opponent_name", nameOf(hostUuid),
				"own_team", guestTeam, "own_team_source", guestSource, "opponent_team_source", hostSource)));
		ghostRecall.recall(hostUuid, "battle started");
		ghostRecall.recall(guestUuid, "battle started");
		if (lobby.timerBy != null) {
			enableTimer(battle, lobby.timerBy);
		}
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
		if (winnerUuid != null && !winnerUuid.equals(battle.hostUuid) && !winnerUuid.equals(battle.guestUuid)) {
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

	/** WebSocket closed: the lobby is cancelled; a battle can't go on — a draw (CAD Partie 1 §44). */
	public synchronized void onDisconnect(UUID playerUuid) {
		invites.values().removeIf(invite -> invite.inviterUuid().equals(playerUuid) || invite.inviteeUuid().equals(playerUuid));
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
		sessionRegistry.send(battle.guestUuid, WsMessage.of("BattleEnded", data));
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
