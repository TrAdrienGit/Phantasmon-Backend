package com.mystaria.phantasmon_backend.battle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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
 *   <li>handles the invitation and picks the host — the inviter for a pair's first battle, then alternating
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
 * only ever sees what the battle itself reveals. In memory like presence; one coarse lock.
 */
@Service
@Slf4j
public class LiveBattleService {

	static final int TIMER_SECONDS = 90;

	private final Map<UUID, LiveBattle> battlesByPlayer = new HashMap<>();
	private final Map<UUID, Invite> invites = new HashMap<>();

	private final BattleRepository battleRepository;
	private final PokemonService pokemonService;
	private final PlayerService playerService;
	private final SessionRegistry sessionRegistry;
	private final GhostRecall ghostRecall;
	private final CobblemonPartyParser partyParser;
	private final Clock clock;
	private final Duration inviteTtl;

	public LiveBattleService(BattleRepository battleRepository, PokemonService pokemonService, PlayerService playerService,
			SessionRegistry sessionRegistry, GhostRecall ghostRecall, CobblemonPartyParser partyParser, Clock clock,
			@Value("${phantasmon.battle.invite-ttl:PT60S}") Duration inviteTtl) {
		this.battleRepository = battleRepository;
		this.pokemonService = pokemonService;
		this.playerService = playerService;
		this.sessionRegistry = sessionRegistry;
		this.ghostRecall = ghostRecall;
		this.partyParser = partyParser;
		this.clock = clock;
		this.inviteTtl = inviteTtl;
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
		} else if (battlesByPlayer.containsKey(inviterUuid)) {
			sendError(inviterUuid, "ERROR_BATTLE_ALREADY_IN_BATTLE");
		} else if (!sessionRegistry.isConnected(targetUuid)) {
			sendError(inviterUuid, "ERROR_BATTLE_PARTNER_UNAVAILABLE");
		} else if (battlesByPlayer.containsKey(targetUuid)) {
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

	/** {@code message}: the {@code BattleInviteResponse} data — the invitee's optional team choice. */
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
		if (battlesByPlayer.containsKey(inviteeUuid)) {
			sendError(inviteeUuid, "ERROR_BATTLE_ALREADY_IN_BATTLE");
			return;
		}
		if (!sessionRegistry.isConnected(inviterUuid)) {
			sendError(inviteeUuid, "ERROR_BATTLE_PARTNER_UNAVAILABLE");
			return;
		}
		if (battlesByPlayer.containsKey(inviterUuid)) {
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
		BattleTeam inviterChoice = invite.inviterTeam();
		List<PokemonResponse> inviterTeam = inviterChoice.isGhost() ? pokemonService.activeTeam(inviterUuid) : inviterChoice.party();
		List<PokemonResponse> inviteeTeam = inviteeChoice.isGhost() ? pokemonService.activeTeam(inviteeUuid) : inviteeChoice.party();
		if (inviterTeam.isEmpty() || inviteeTeam.isEmpty()) {
			sendError(inviteeUuid, "ERROR_BATTLE_EMPTY_TEAM");
			sendError(inviterUuid, "ERROR_BATTLE_EMPTY_TEAM");
			return;
		}

		UUID hostUuid = nextHost(inviterUuid, inviteeUuid);
		UUID guestUuid = hostUuid.equals(inviterUuid) ? inviteeUuid : inviterUuid;
		List<PokemonResponse> hostTeam = hostUuid.equals(inviterUuid) ? inviterTeam : inviteeTeam;
		List<PokemonResponse> guestTeam = hostUuid.equals(inviterUuid) ? inviteeTeam : inviterTeam;
		String hostSource = (hostUuid.equals(inviterUuid) ? inviterChoice : inviteeChoice).source().name();
		String guestSource = (hostUuid.equals(inviterUuid) ? inviteeChoice : inviterChoice).source().name();

		BattleSession session = BattleSession.hosted(UUID.randomUUID(), hostUuid, guestUuid,
				hostTeam.stream().map(PokemonResponse::uuid).toList(), guestTeam.stream().map(PokemonResponse::uuid).toList());
		battleRepository.save(session);
		LiveBattle battle = new LiveBattle(session.getUuid(), hostUuid, guestUuid);
		battlesByPlayer.put(hostUuid, battle);
		battlesByPlayer.put(guestUuid, battle);
		log.info("Live battle {} started: host {} vs guest {}", battle.uuid, hostUuid, guestUuid);

		sessionRegistry.send(hostUuid, WsMessage.of("BattleSessionStarted", Map.of(
				"battle_uuid", battle.uuid, "role", "HOST",
				"opponent_uuid", guestUuid, "opponent_name", nameOf(guestUuid),
				"own_team", hostTeam, "opponent_team", guestTeam,
				"own_team_source", hostSource, "opponent_team_source", guestSource)));
		sessionRegistry.send(guestUuid, WsMessage.of("BattleSessionStarted", Map.of(
				"battle_uuid", battle.uuid, "role", "GUEST",
				"opponent_uuid", hostUuid, "opponent_name", nameOf(hostUuid),
				"own_team", guestTeam, "own_team_source", guestSource, "opponent_team_source", hostSource)));
		// Both players' Ghosts go back in for the whole battle (TODO-14); ifNotInBattle keeps them in.
		ghostRecall.recall(hostUuid, "battle started");
		ghostRecall.recall(guestUuid, "battle started");
	}

	/**
	 * Runs {@code action} only if the player is not in a live battle, under the same lock as a battle start, so a
	 * Ghost can never be sent out between the start's recall and the battle being registered. Returns whether it ran.
	 */
	public synchronized boolean ifNotInBattle(UUID playerUuid, Runnable action) {
		if (battlesByPlayer.containsKey(playerUuid)) {
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
		battle.timerEnabled = true;
		WsMessage enabled = WsMessage.of("BattleTimerEnabled", Map.of(
				"battle_uuid", battle.uuid, "by_uuid", senderUuid, "by_name", nameOf(senderUuid), "seconds", TIMER_SECONDS));
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

	/** WebSocket closed: the battle can't go on — a draw (CAD Partie 1 §44), nobody's Ghost Pokémon is affected. */
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

	public synchronized void onDisconnect(UUID playerUuid) {
		invites.values().removeIf(invite -> invite.inviterUuid().equals(playerUuid) || invite.inviteeUuid().equals(playerUuid));
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
