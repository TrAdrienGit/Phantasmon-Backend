package com.mystaria.phantasmon_backend.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/** Tracks the open WebSocket session for each connected player. */
@Component
@Slf4j
public class SessionRegistry {

	private final Map<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;

	public SessionRegistry(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void register(UUID playerUuid, WebSocketSession session) {
		sessions.put(playerUuid, session);
	}

	public void unregister(UUID playerUuid) {
		sessions.remove(playerUuid);
	}

	/**
	 * Best-effort push to a specific player — silently does nothing if they
	 * are not currently connected (no offline notification queue in V1).
	 */
	public void send(UUID playerUuid, WsMessage message) {
		WebSocketSession session = sessions.get(playerUuid);
		if (session == null || !session.isOpen()) {
			return;
		}
		try {
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
		} catch (IOException ex) {
			log.warn("Failed to send WebSocket message to {}", playerUuid, ex);
		}
	}

	public void close(UUID playerUuid) {
		WebSocketSession session = sessions.remove(playerUuid);
		if (session != null && session.isOpen()) {
			try {
				session.close(CloseStatus.GOING_AWAY);
			} catch (IOException ex) {
				log.warn("Failed to close stale WebSocket session for {}", playerUuid, ex);
			}
		}
	}
}
