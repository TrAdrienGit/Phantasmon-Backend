package com.mystaria.phantasmon_backend.websocket;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.mystaria.phantasmon_backend.presence.PresenceService;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dispatches C2S presence messages (CAD Partie 2 §11). Ghost Entity, trade,
 * and battle events are added when those domains exist — this is the Phase 2
 * skeleton (presence + heartbeat only).
 */
@Component
@Slf4j
public class PhantasmonWebSocketHandler extends TextWebSocketHandler {

	private final PresenceService presenceService;
	private final SessionRegistry sessionRegistry;
	private final ObjectMapper objectMapper;

	public PhantasmonWebSocketHandler(PresenceService presenceService, SessionRegistry sessionRegistry, ObjectMapper objectMapper) {
		this.presenceService = presenceService;
		this.sessionRegistry = sessionRegistry;
		this.objectMapper = objectMapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		sessionRegistry.register(playerUuid(session), session);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		UUID playerUuid = playerUuid(session);
		presenceService.leave(playerUuid);
		sessionRegistry.unregister(playerUuid);
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
				presenceService.join(playerUuid, fingerprint, dimension);
			}
			case "LeaveServerGroup" -> presenceService.leave(playerUuid);
			case "PositionUpdate" -> presenceService.updatePosition(playerUuid,
					numberField(incoming, "x"), numberField(incoming, "y"), numberField(incoming, "z"),
					stringField(incoming, "dimension"));
			case "Heartbeat" -> {
				presenceService.heartbeat(playerUuid);
				send(session, WsMessage.of("HeartbeatAck", Map.of()));
			}
			default -> send(session, WsMessage.error("ERROR_WS_UNKNOWN_MESSAGE_TYPE", Map.of("type", incoming.type())));
		}
	}

	private void send(WebSocketSession session, WsMessage message) throws Exception {
		session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
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
}
