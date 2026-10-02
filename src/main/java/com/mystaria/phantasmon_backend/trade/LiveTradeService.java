package com.mystaria.phantasmon_backend.trade;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerService;
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;
import com.mystaria.phantasmon_backend.pokemon.PokemonService;
import com.mystaria.phantasmon_backend.presence.PresenceService;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Live trade sessions behind the graphical trade screen (Adrien 2026-10-02):
 * invitation, then both players see each other's active team, pick their
 * offer and flag themselves ready; once both are ready the swap is executed
 * atomically by {@link TradeService#completeLiveTrade}. Everything travels
 * over the presence WebSocket (C2S {@code TradeInvite}/{@code
 * TradeInviteResponse}/{@code TradeSelectOffer}/{@code TradeSetReady}/{@code
 * TradeLeave}, dispatched by {@code PhantasmonWebSocketHandler}) — see
 * {@code PHANTASMON_API_REFERENCE.md} for the exact payloads.
 *
 * <p>Complements, does not replace, the asynchronous {@code POST /trades}
 * propose/accept flow. In-memory only (like presence, single backend
 * instance, CAD Partie 3 §H); one coarse lock guards every session/invite
 * since there are only ever a handful of trades at once.
 *
 * <p>Business-rule rejections never reach the generic {@code Error} message:
 * they're sent as {@code TradeSessionError} so the client can show them on the
 * trade screen itself rather than as unrelated chat noise.
 */
@Service
@Slf4j
public class LiveTradeService {

	private final Map<UUID, LiveTradeSession> sessionsByPlayer = new HashMap<>();
	private final Map<UUID, LiveTradeInvite> invites = new HashMap<>();

	private final TradeService tradeService;
	private final PokemonService pokemonService;
	private final PokemonRepository pokemonRepository;
	private final PlayerService playerService;
	private final PresenceService presenceService;
	private final SessionRegistry sessionRegistry;
	private final Clock clock;
	private final Duration inviteTtl;

	public LiveTradeService(TradeService tradeService, PokemonService pokemonService, PokemonRepository pokemonRepository,
			PlayerService playerService, PresenceService presenceService, SessionRegistry sessionRegistry, Clock clock,
			@Value("${phantasmon.trade.invite-ttl:PT60S}") Duration inviteTtl) {
		this.tradeService = tradeService;
		this.pokemonService = pokemonService;
		this.pokemonRepository = pokemonRepository;
		this.playerService = playerService;
		this.presenceService = presenceService;
		this.sessionRegistry = sessionRegistry;
		this.clock = clock;
		this.inviteTtl = inviteTtl;
	}

	public synchronized void invite(UUID inviterUuid, UUID targetUuid) {
		if (targetUuid == null) {
			sendError(inviterUuid, "ERROR_WS_MALFORMED_MESSAGE");
			return;
		}
		if (inviterUuid.equals(targetUuid)) {
			sendError(inviterUuid, "ERROR_TRADE_SELF");
			return;
		}
		if (sessionsByPlayer.containsKey(inviterUuid)) {
			sendError(inviterUuid, "ERROR_TRADE_ALREADY_IN_SESSION");
			return;
		}
		if (!sessionRegistry.isConnected(targetUuid)) {
			sendError(inviterUuid, "ERROR_TRADE_PARTNER_UNAVAILABLE");
			return;
		}
		if (sessionsByPlayer.containsKey(targetUuid)) {
			sendError(inviterUuid, "ERROR_TRADE_PARTNER_BUSY");
			return;
		}

		invites.values().removeIf(existing -> existing.inviterUuid().equals(inviterUuid) && existing.inviteeUuid().equals(targetUuid));
		LiveTradeInvite invite = new LiveTradeInvite(UUID.randomUUID(), inviterUuid, targetUuid, clock.instant());
		invites.put(invite.uuid(), invite);

		log.info("Live trade invite {}: {} invited {}", invite.uuid(), inviterUuid, targetUuid);
		sessionRegistry.send(targetUuid, WsMessage.of("TradeInviteReceived", Map.of(
				"invite_uuid", invite.uuid(), "from_uuid", inviterUuid, "from_name", nameOf(inviterUuid))));
		sessionRegistry.send(inviterUuid, WsMessage.of("TradeInviteSent", Map.of(
				"invite_uuid", invite.uuid(), "to_uuid", targetUuid, "to_name", nameOf(targetUuid))));
	}

	public synchronized void respond(UUID inviteeUuid, UUID inviteUuid, boolean accept) {
		LiveTradeInvite invite = inviteUuid == null ? null : invites.get(inviteUuid);
		if (invite == null || !invite.inviteeUuid().equals(inviteeUuid) || isExpired(invite)) {
			if (invite != null && isExpired(invite)) {
				invites.remove(inviteUuid);
			}
			sendError(inviteeUuid, "ERROR_TRADE_INVITE_NOT_FOUND");
			return;
		}
		invites.remove(inviteUuid);
		UUID inviterUuid = invite.inviterUuid();

		if (!accept) {
			log.info("Live trade invite {} declined by {}", inviteUuid, inviteeUuid);
			sessionRegistry.send(inviterUuid, WsMessage.of("TradeInviteDeclined", Map.of(
					"invite_uuid", inviteUuid, "by_uuid", inviteeUuid, "by_name", nameOf(inviteeUuid))));
			return;
		}
		if (sessionsByPlayer.containsKey(inviteeUuid)) {
			sendError(inviteeUuid, "ERROR_TRADE_ALREADY_IN_SESSION");
			return;
		}
		if (!sessionRegistry.isConnected(inviterUuid)) {
			sendError(inviteeUuid, "ERROR_TRADE_PARTNER_UNAVAILABLE");
			return;
		}
		if (sessionsByPlayer.containsKey(inviterUuid)) {
			sendError(inviteeUuid, "ERROR_TRADE_PARTNER_BUSY");
			return;
		}

		LiveTradeSession session = new LiveTradeSession(UUID.randomUUID(),
				inviterUuid, nameOf(inviterUuid), inviteeUuid, nameOf(inviteeUuid));
		sessionsByPlayer.put(inviterUuid, session);
		sessionsByPlayer.put(inviteeUuid, session);
		log.info("Live trade session {} started between {} and {}", session.uuid(), inviterUuid, inviteeUuid);

		sendSessionStarted(session, inviterUuid);
		sendSessionStarted(session, inviteeUuid);
	}

	public synchronized void selectOffer(UUID playerUuid, UUID pokemonUuid) {
		LiveTradeSession session = sessionsByPlayer.get(playerUuid);
		if (session == null) {
			sendError(playerUuid, "ERROR_TRADE_NOT_IN_SESSION");
			return;
		}
		Pokemon pokemon = pokemonUuid == null ? null : pokemonRepository.findById(pokemonUuid).orElse(null);
		if (pokemon == null) {
			sendError(playerUuid, "ERROR_POKEMON_NOT_FOUND");
			return;
		}
		if (!pokemon.getOwnerUuid().equals(playerUuid)) {
			sendError(playerUuid, "ERROR_OWNERSHIP_MISMATCH");
			return;
		}
		if (pokemon.getTeamSlot() == null) {
			sendError(playerUuid, "ERROR_TRADE_OFFER_NOT_IN_TEAM");
			return;
		}
		session.setOffer(playerUuid, pokemonUuid);
		broadcastUpdate(session);
	}

	public synchronized void setReady(UUID playerUuid, boolean ready) {
		LiveTradeSession session = sessionsByPlayer.get(playerUuid);
		if (session == null) {
			sendError(playerUuid, "ERROR_TRADE_NOT_IN_SESSION");
			return;
		}
		if (ready && !session.bothOffersChosen()) {
			sendError(playerUuid, "ERROR_TRADE_OFFERS_INCOMPLETE");
			return;
		}
		session.setReady(playerUuid, ready);
		broadcastUpdate(session);
		if (session.bothReady()) {
			complete(session);
		}
	}

	/** Explicit "QUITTER" from either side. */
	public synchronized void leave(UUID playerUuid) {
		cancel(playerUuid, "PARTNER_LEFT");
	}

	/** WebSocket closed (clean or abrupt): ends any session and forgets every invite involving this player. */
	public synchronized void onDisconnect(UUID playerUuid) {
		invites.values().removeIf(invite -> invite.inviterUuid().equals(playerUuid) || invite.inviteeUuid().equals(playerUuid));
		cancel(playerUuid, "PARTNER_DISCONNECTED");
	}

	private void cancel(UUID playerUuid, String reasonForPartner) {
		LiveTradeSession session = sessionsByPlayer.get(playerUuid);
		if (session == null) {
			return;
		}
		end(session);
		UUID partnerUuid = session.partnerOf(playerUuid);
		log.info("Live trade session {} cancelled by {} ({})", session.uuid(), playerUuid, reasonForPartner);
		sessionRegistry.send(partnerUuid, WsMessage.of("TradeSessionCancelled", Map.of(
				"session_uuid", session.uuid(), "reason", reasonForPartner)));
	}

	private void complete(LiveTradeSession session) {
		UUID initiatorUuid = session.initiatorUuid();
		UUID recipientUuid = session.recipientUuid();
		UUID initiatorOffer = session.offerOf(initiatorUuid);
		UUID recipientOffer = session.offerOf(recipientUuid);
		end(session);

		TradeResponse trade;
		try {
			trade = tradeService.completeLiveTrade(initiatorUuid, initiatorOffer, recipientUuid, recipientOffer);
		} catch (RuntimeException ex) {
			// Anything escaping here would also make Spring close the caller's WebSocket.
			String reason = ex instanceof ApiException apiException ? apiException.getErrorCode() : "ERROR_UNKNOWN";
			if (ex instanceof ApiException) {
				log.info("Live trade session {} failed at completion: {}", session.uuid(), reason);
			} else {
				log.error("Live trade session {} failed at completion", session.uuid(), ex);
			}
			WsMessage cancelled = WsMessage.of("TradeSessionCancelled", Map.of(
					"session_uuid", session.uuid(), "reason", reason));
			sessionRegistry.send(initiatorUuid, cancelled);
			sessionRegistry.send(recipientUuid, cancelled);
			return;
		}

		sessionRegistry.send(initiatorUuid, WsMessage.of("TradeSessionCompleted", Map.of(
				"session_uuid", session.uuid(), "trade_uuid", trade.uuid(),
				"given_pokemon", initiatorOffer, "received_pokemon", recipientOffer)));
		sessionRegistry.send(recipientUuid, WsMessage.of("TradeSessionCompleted", Map.of(
				"session_uuid", session.uuid(), "trade_uuid", trade.uuid(),
				"given_pokemon", recipientOffer, "received_pokemon", initiatorOffer)));

		recallGhostIfTraded(initiatorUuid, initiatorOffer);
		recallGhostIfTraded(recipientUuid, recipientOffer);
	}

	/**
	 * A Pokémon that was out as a Ghost just changed owner: its former owner's
	 * presence must stop pointing at it, and everyone who sees that Ghost
	 * (group + the owner, same audience as {@code RecallGhost}) gets a despawn.
	 */
	private void recallGhostIfTraded(UUID playerUuid, UUID tradedPokemonUuid) {
		presenceService.find(playerUuid)
				.filter(presence -> tradedPokemonUuid.equals(presence.activeGhostPokemonUuid()))
				.ifPresent(presence -> {
					List<UUID> groupMembers = presenceService.groupMembers(playerUuid);
					presenceService.recallGhost(playerUuid);
					WsMessage despawn = WsMessage.of("GhostEntityDespawn",
							Map.of("player_uuid", playerUuid, "pokemon_uuid", tradedPokemonUuid));
					groupMembers.forEach(member -> sessionRegistry.send(member, despawn));
					sessionRegistry.send(playerUuid, despawn);
				});
	}

	private void end(LiveTradeSession session) {
		sessionsByPlayer.remove(session.initiatorUuid());
		sessionsByPlayer.remove(session.recipientUuid());
	}

	private void sendSessionStarted(LiveTradeSession session, UUID playerUuid) {
		UUID partnerUuid = session.partnerOf(playerUuid);
		sessionRegistry.send(playerUuid, WsMessage.of("TradeSessionStarted", Map.of(
				"session_uuid", session.uuid(),
				"partner_uuid", partnerUuid,
				"partner_name", session.nameOf(partnerUuid),
				"own_team", pokemonService.activeTeam(playerUuid),
				"partner_team", pokemonService.activeTeam(partnerUuid))));
	}

	/** Each side gets its own perspective (own vs partner), so the client never has to work out which player it is. */
	private void broadcastUpdate(LiveTradeSession session) {
		sendUpdate(session, session.initiatorUuid());
		sendUpdate(session, session.recipientUuid());
	}

	private void sendUpdate(LiveTradeSession session, UUID playerUuid) {
		UUID partnerUuid = session.partnerOf(playerUuid);
		Map<String, Object> data = new HashMap<>();
		data.put("session_uuid", session.uuid());
		data.put("own_offer", session.offerOf(playerUuid));
		data.put("partner_offer", session.offerOf(partnerUuid));
		data.put("own_ready", session.isReady(playerUuid));
		data.put("partner_ready", session.isReady(partnerUuid));
		sessionRegistry.send(playerUuid, WsMessage.of("TradeSessionUpdate", data));
	}

	private void sendError(UUID playerUuid, String errorCode) {
		sessionRegistry.send(playerUuid, WsMessage.of("TradeSessionError", Map.of("error_code", errorCode)));
	}

	private boolean isExpired(LiveTradeInvite invite) {
		return invite.createdAt().plus(inviteTtl).isBefore(clock.instant());
	}

	private String nameOf(UUID playerUuid) {
		return playerService.findById(playerUuid).map(Player::getLastUsername).orElse("?");
	}

	private record LiveTradeInvite(UUID uuid, UUID inviterUuid, UUID inviteeUuid, Instant createdAt) {
	}
}
