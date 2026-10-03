package com.mystaria.phantasmon_backend.websocket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;
import com.mystaria.phantasmon_backend.presence.PlayerPresence;
import com.mystaria.phantasmon_backend.presence.Position;
import com.mystaria.phantasmon_backend.presence.PresenceService;
import com.mystaria.phantasmon_backend.battle.LiveBattleService;
import com.mystaria.phantasmon_backend.trade.LiveTradeService;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dispatches C2S presence and Ghost Entity messages (CAD Partie 2 §7/§8/§11,
 * client Phase 7). Live trade C2S messages ({@code Trade*}) are only routed
 * here — their logic lives in {@link LiveTradeService}; the asynchronous
 * {@code POST /trades} events are pushed from {@code TradeService}. Battle WS
 * events arrive with backend/client Phase 9.
 *
 * <p>Ghost Entity movement deliberately has no dedicated C2S message: a
 * player's ghost follows its owner (CAD §7), so a {@code GhostEntityMove} is
 * just piggybacked onto the existing {@code PositionUpdate} heartbeat when
 * that player currently has a Ghost out — no separate timer/message needed.
 */
@Component
@Slf4j
public class PhantasmonWebSocketHandler extends TextWebSocketHandler {

	private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

	private final PresenceService presenceService;
	private final SessionRegistry sessionRegistry;
	private final PokemonRepository pokemonRepository;
	private final LiveTradeService liveTradeService;
	private final LiveBattleService liveBattleService;
	private final ObjectMapper objectMapper;

	public PhantasmonWebSocketHandler(PresenceService presenceService, SessionRegistry sessionRegistry,
			PokemonRepository pokemonRepository, LiveTradeService liveTradeService, LiveBattleService liveBattleService,
			ObjectMapper objectMapper) {
		this.presenceService = presenceService;
		this.sessionRegistry = sessionRegistry;
		this.pokemonRepository = pokemonRepository;
		this.liveTradeService = liveTradeService;
		this.liveBattleService = liveBattleService;
		this.objectMapper = objectMapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		UUID playerUuid = playerUuid(session);
		log.info("WebSocket connected: player {}", playerUuid);
		// Tomcat's default 8 KiB message buffer would close the connection on a bigger frame; a live battle relays
		// whole encoded Cobblemon packets (a team packet carries six full Pokémon), so allow up to 1 MiB.
		session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
		session.setBinaryMessageSizeLimit(MAX_MESSAGE_BYTES);
		sessionRegistry.register(playerUuid, session);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		UUID playerUuid = playerUuid(session);
		log.info("WebSocket closed: player {} ({})", playerUuid, status);
		leaveAndDespawnGhost(playerUuid);
		liveTradeService.onDisconnect(playerUuid);
		liveBattleService.onDisconnect(playerUuid);
		sessionRegistry.unregister(playerUuid);
	}

	/** Leaves the presence group and, if the player had a Ghost out, broadcasts its despawn first (CAD §8: abrupt disconnect must still despawn). */
	private void leaveAndDespawnGhost(UUID playerUuid) {
		List<UUID> groupMembers = presenceService.groupMembers(playerUuid);
		presenceService.leave(playerUuid).ifPresent(presence -> {
			if (presence.activeGhostPokemonUuid() != null) {
				WsMessage despawn = WsMessage.of("GhostEntityDespawn",
						Map.of("player_uuid", playerUuid, "pokemon_uuid", presence.activeGhostPokemonUuid()));
				broadcast(groupMembers, despawn);
				sessionRegistry.send(playerUuid, despawn);
			}
		});
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		UUID playerUuid = playerUuid(session);
		WsMessage incoming;
		try {
			JsonNode node = objectMapper.readTree(message.getPayload());
			incoming = objectMapper.treeToValue(node, WsMessage.class);
		} catch (Exception ex) {
			send(session, WsMessage.error("ERROR_WS_MALFORMED_MESSAGE", Map.of()));
			return;
		}

		switch (incoming.type()) {
			case "JoinServerGroup" -> {
				String fingerprint = stringField(incoming, "server_fingerprint");
				String dimension = stringField(incoming, "dimension");
				log.info("Player {} joined group fingerprint={} dimension={}", playerUuid, fingerprint, dimension);
				presenceService.join(playerUuid, fingerprint, dimension);
				sendGhostCatchUp(session, playerUuid);
			}
			case "LeaveServerGroup" -> leaveAndDespawnGhost(playerUuid);
			case "PositionUpdate" -> {
				presenceService.updatePosition(playerUuid,
						numberField(incoming, "x"), numberField(incoming, "y"), numberField(incoming, "z"),
						stringField(incoming, "dimension"));
				presenceService.find(playerUuid)
						.filter(presence -> presence.activeGhostPokemonUuid() != null)
						.ifPresent(presence -> broadcastToGroupAndSelf(playerUuid,
								WsMessage.of("GhostEntityMove", Map.of("player_uuid", playerUuid,
										"pokemon_uuid", presence.activeGhostPokemonUuid(), "position", presence.position()))));
			}
			case "SendOutGhost" -> handleSendOutGhost(session, playerUuid, uuidField(incoming, "pokemon_uuid"));
			case "RecallGhost" -> handleRecallGhost(playerUuid);
			case "TradeInvite" -> liveTradeService.invite(playerUuid, uuidField(incoming, "target_uuid"));
			case "TradeInviteResponse" -> liveTradeService.respond(playerUuid, uuidField(incoming, "invite_uuid"),
					Boolean.TRUE.equals(incoming.data().get("accept")));
			case "TradeSelectOffer" -> liveTradeService.selectOffer(playerUuid, uuidField(incoming, "pokemon_uuid"));
			case "TradeSetReady" -> liveTradeService.setReady(playerUuid, Boolean.TRUE.equals(incoming.data().get("ready")));
			case "TradeLeave" -> liveTradeService.leave(playerUuid);
			case "BattleInvite" -> liveBattleService.invite(playerUuid, uuidField(incoming, "target_uuid"));
			case "BattleInviteResponse" -> liveBattleService.respond(playerUuid, uuidField(incoming, "invite_uuid"),
					Boolean.TRUE.equals(incoming.data().get("accept")));
			case "BattlePacket" -> liveBattleService.relayPacket(playerUuid, uuidField(incoming, "battle_uuid"), incoming.data());
			case "BattleChoice" -> liveBattleService.relayChoice(playerUuid, uuidField(incoming, "battle_uuid"), incoming.data());
			case "BattleTimerEnable" -> liveBattleService.enableTimer(playerUuid, uuidField(incoming, "battle_uuid"));
			case "BattleResult" -> liveBattleService.reportResult(playerUuid, uuidField(incoming, "battle_uuid"),
					uuidField(incoming, "winner_uuid"));
			case "BattleLeave" -> liveBattleService.leave(playerUuid, uuidField(incoming, "battle_uuid"));
			case "Heartbeat" -> {
				presenceService.heartbeat(playerUuid);
				send(session, WsMessage.of("HeartbeatAck", Map.of()));
			}
			default -> send(session, WsMessage.error("ERROR_WS_UNKNOWN_MESSAGE_TYPE", Map.of("type", incoming.type())));
		}
	}

	private void handleSendOutGhost(WebSocketSession session, UUID playerUuid, UUID pokemonUuid) throws Exception {
		if (pokemonUuid == null) {
			send(session, WsMessage.error("ERROR_WS_MALFORMED_MESSAGE", Map.of()));
			return;
		}
		Pokemon pokemon = pokemonRepository.findById(pokemonUuid).orElse(null);
		if (pokemon == null) {
			send(session, WsMessage.error("ERROR_POKEMON_NOT_FOUND", Map.of("uuid", pokemonUuid)));
			return;
		}
		if (!pokemon.getOwnerUuid().equals(playerUuid)) {
			send(session, WsMessage.error("ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", pokemonUuid)));
			return;
		}
		if (pokemon.getTeamSlot() == null) {
			send(session, WsMessage.error("ERROR_POKEMON_NOT_IN_TEAM", Map.of("uuid", pokemonUuid)));
			return;
		}

		presenceService.sendOutGhost(playerUuid, pokemonUuid);
		Position position = presenceService.find(playerUuid).map(PlayerPresence::position).orElse(null);
		List<UUID> groupMembers = presenceService.groupMembers(playerUuid);
		log.info("Player {} sent out Ghost {} ({}) — broadcasting to {} group member(s) + self",
				playerUuid, pokemonUuid, pokemon.getSpecies(), groupMembers.size());
		broadcastToGroupAndSelf(playerUuid, WsMessage.of("GhostEntitySpawn",
				ghostSpawnData(playerUuid, pokemon, position)));
	}

	private void handleRecallGhost(UUID playerUuid) {
		presenceService.find(playerUuid)
				.map(PlayerPresence::activeGhostPokemonUuid)
				.ifPresent(activeGhostUuid -> {
					presenceService.recallGhost(playerUuid);
					log.info("Player {} recalled Ghost {}", playerUuid, activeGhostUuid);
					broadcastToGroupAndSelf(playerUuid, WsMessage.of("GhostEntityDespawn",
							Map.of("player_uuid", playerUuid, "pokemon_uuid", activeGhostUuid)));
				});
	}

	/** A player who just joined a group must be told about Ghosts already out among their new group members. */
	private void sendGhostCatchUp(WebSocketSession session, UUID playerUuid) throws Exception {
		for (UUID memberUuid : presenceService.groupMembers(playerUuid)) {
			presenceService.find(memberUuid)
					.filter(presence -> presence.activeGhostPokemonUuid() != null)
					.ifPresent(presence -> pokemonRepository.findById(presence.activeGhostPokemonUuid()).ifPresent(pokemon -> {
						try {
							send(session, WsMessage.of("GhostEntitySpawn", ghostSpawnData(memberUuid, pokemon, presence.position())));
						} catch (Exception ex) {
							log.warn("Failed to send Ghost catch-up to {}", playerUuid, ex);
						}
					}));
		}
	}

	/**
	 * {@code position} may still be null right after {@code SendOutGhost} if the
	 * player hasn't sent a {@code PositionUpdate} yet — {@code Map.of} would NPE
	 * on that, so build the map manually. Includes the Pokémon's rendering-
	 * relevant identifiers (species/form/shiny/level) because the *receiving*
	 * client has no way to look up someone else's Pokémon over REST (ownership-
	 * gated routes only expose the caller's own) — this WS payload is the only
	 * place that data can come from.
	 */
	private static Map<String, Object> ghostSpawnData(UUID playerUuid, Pokemon pokemon, Position position) {
		Map<String, Object> data = new HashMap<>();
		data.put("player_uuid", playerUuid);
		data.put("pokemon_uuid", pokemon.getUuid());
		data.put("species", pokemon.getSpecies());
		data.put("form", pokemon.getForm());
		data.put("is_shiny", pokemon.isShiny());
		data.put("level", pokemon.getLevel());
		data.put("position", position);
		return data;
	}

	private void broadcast(List<UUID> recipients, WsMessage message) {
		recipients.forEach(recipient -> sessionRegistry.send(recipient, message));
	}

	/**
	 * {@code PresenceService.groupMembers} deliberately excludes the caller
	 * (it answers "who else is in my group"), but a Ghost's owner must also see
	 * their own Ghost appear/move/disappear locally — otherwise a lone player
	 * sending their Ghost out sees nothing at all, which is indistinguishable
	 * from the feature being broken (found via Adrien's manual QA, 2026-09-26).
	 */
	private void broadcastToGroupAndSelf(UUID playerUuid, WsMessage message) {
		broadcast(presenceService.groupMembers(playerUuid), message);
		sessionRegistry.send(playerUuid, message);
	}

	private void send(WebSocketSession session, WsMessage message) throws Exception {
		SessionRegistry.sendTo(session, objectMapper.writeValueAsString(message));
	}

	private static UUID playerUuid(WebSocketSession session) {
		return (UUID) session.getAttributes().get(JwtHandshakeInterceptor.PLAYER_UUID_ATTRIBUTE);
	}

	private static String stringField(WsMessage message, String key) {
		Object value = message.data().get(key);
		return value == null ? null : value.toString();
	}

	private static double numberField(WsMessage message, String key) {
		Object value = message.data().get(key);
		return value instanceof Number number ? number.doubleValue() : 0;
	}

	private static UUID uuidField(WsMessage message, String key) {
		Object value = message.data().get(key);
		try {
			return value == null ? null : UUID.fromString(value.toString());
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
