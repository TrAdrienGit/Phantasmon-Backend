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

	/**
	 * One connection per player: a new one replaces the previous, which is closed (SEC-8). Its close then finds it
	 * is no longer the registered one ({@link #unregister}) and leaves the player's state alone.
	 */
	public void register(UUID playerUuid, WebSocketSession session) {
		WebSocketSession previous = sessions.put(playerUuid, session);
		if (previous != null && previous != session && previous.isOpen()) {
			log.info("Player {} reconnected — closing the previous WebSocket session", playerUuid);
			try {
				previous.close(CloseStatus.POLICY_VIOLATION.withReason("Replaced by a newer connection"));
			} catch (IOException ex) {
				log.warn("Failed to close the replaced WebSocket session of {}", playerUuid, ex);
			}
		}
	}

	/** Removes {@code session} only if it is still the player's registered one; returns whether it was. */
	public boolean unregister(UUID playerUuid, WebSocketSession session) {
		return sessions.remove(playerUuid, session);
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
			sendTo(session, objectMapper.writeValueAsString(message));
		} catch (IOException | IllegalStateException ex) {
			log.warn("Failed to send WebSocket message to {}", playerUuid, ex);
		}
	}

	/**
	 * A raw {@link WebSocketSession} must never be written to by two threads at
	 * once (Tomcat throws {@code IllegalStateException: TEXT_PARTIAL_WRITING}).
	 * That happens for real once two players' handler threads both push to the
	 * same partner session — a live trade does exactly that — so every write
	 * goes through this one lock-per-session helper, the handler's direct
	 * replies included.
	 */
	public static void sendTo(WebSocketSession session, String payload) throws IOException {
		synchronized (session) {
			session.sendMessage(new TextMessage(payload));
		}
	}

	public boolean isConnected(UUID playerUuid) {
		WebSocketSession session = sessions.get(playerUuid);
		return session != null && session.isOpen();
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
