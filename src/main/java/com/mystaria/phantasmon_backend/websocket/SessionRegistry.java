package com.mystaria.phantasmon_backend.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Tracks the open WebSocket session for each connected player, and is the only place that writes to a session.
 *
 * <p>Every session is wrapped in a {@link ConcurrentWebSocketSessionDecorator}: a raw session must never be written by
 * two threads at once (Tomcat's {@code TEXT_PARTIAL_WRITING}), and a lock per session (the previous design) made every
 * sender wait for the slowest receiver — one player on a bad connection then held {@code LiveBattleService}'s or the
 * hub's thread while everyone else's messages waited. With the decorator, the thread that finds the session idle
 * sends; the others queue their message and return at once. A receiver that stays stuck past {@link #SEND_TIME_LIMIT_MS}
 * or lets {@link #BUFFER_LIMIT_BYTES} pile up is disconnected (its client reconnects), instead of slowing the backend.
 */
@Component
@Slf4j
public class SessionRegistry {

	/** One send may not block longer than this; past it, the session is closed. */
	static final int SEND_TIME_LIMIT_MS = 10_000;
	/** Messages queued for one slow receiver, at most (mirror hub terrain bursts included). */
	static final int BUFFER_LIMIT_BYTES = 8 * 1024 * 1024;

	private final Map<UUID, ConcurrentWebSocketSessionDecorator> sessions = new ConcurrentHashMap<>();
	/** Raw session → its decorator, for the replies the handler sends on it (sessions compare by identity). */
	private final Map<WebSocketSession, ConcurrentWebSocketSessionDecorator> byRawSession = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;

	public SessionRegistry(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * One connection per player: a new one replaces the previous, which is closed (SEC-8). Its close then finds it
	 * is no longer the registered one ({@link #unregister}) and leaves the player's state alone.
	 */
	public void register(UUID playerUuid, WebSocketSession session) {
		ConcurrentWebSocketSessionDecorator decorated = decorate(session);
		ConcurrentWebSocketSessionDecorator previous = sessions.put(playerUuid, decorated);
		if (previous != null && previous != decorated && previous.isOpen()) {
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
		ConcurrentWebSocketSessionDecorator decorated = byRawSession.remove(session);
		return decorated != null && sessions.remove(playerUuid, decorated);
	}

	/**
	 * Best-effort push to a specific player — silently does nothing if they
	 * are not currently connected (no offline notification queue in V1).
	 */
	public void send(UUID playerUuid, WsMessage message) {
		ConcurrentWebSocketSessionDecorator session = sessions.get(playerUuid);
		if (session == null || !session.isOpen()) {
			return;
		}
		write(playerUuid, session, new TextMessage(serialize(message)));
	}

	/**
	 * Best-effort push of one message to several players: serialized once, not once per receiver (hub moves, voice
	 * status, battle spectators — the hot relays).
	 */
	public void sendAll(Iterable<UUID> playerUuids, WsMessage message) {
		TextMessage text = null;
		for (UUID playerUuid : playerUuids) {
			ConcurrentWebSocketSessionDecorator session = sessions.get(playerUuid);
			if (session == null || !session.isOpen()) {
				continue;
			}
			if (text == null) {
				text = new TextMessage(serialize(message));
			}
			write(playerUuid, session, text);
		}
	}

	/** Best-effort binary push (hub voice, D-36). */
	public void sendBinary(UUID playerUuid, byte[] payload) {
		ConcurrentWebSocketSessionDecorator session = sessions.get(playerUuid);
		if (session == null || !session.isOpen()) {
			return;
		}
		write(playerUuid, session, new BinaryMessage(payload));
	}

	/** Best-effort push to every connected player. */
	public void broadcast(WsMessage message) {
		sendAll(sessions.keySet(), message);
	}

	/**
	 * A reply on the session a message came in on (the handler's direct answers), through the same decorator as every
	 * other write to it; a session not registered (yet, or any more) gets its own short-lived one.
	 */
	public void reply(WebSocketSession session, String payload) throws IOException {
		ConcurrentWebSocketSessionDecorator decorated = byRawSession.get(session);
		if (decorated == null) {
			decorated = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
		}
		decorated.sendMessage(new TextMessage(payload));
	}

	public boolean isConnected(UUID playerUuid) {
		WebSocketSession session = sessions.get(playerUuid);
		return session != null && session.isOpen();
	}

	public void close(UUID playerUuid) {
		ConcurrentWebSocketSessionDecorator session = sessions.remove(playerUuid);
		if (session != null) {
			byRawSession.remove(session.getDelegate(), session);
			if (session.isOpen()) {
				try {
					session.close(CloseStatus.GOING_AWAY);
				} catch (IOException ex) {
					log.warn("Failed to close stale WebSocket session for {}", playerUuid, ex);
				}
			}
		}
	}

	private ConcurrentWebSocketSessionDecorator decorate(WebSocketSession session) {
		return byRawSession.computeIfAbsent(session,
				raw -> new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES));
	}

	private String serialize(WsMessage message) {
		return objectMapper.writeValueAsString(message);
	}

	private static void write(UUID playerUuid, WebSocketSession session, WebSocketMessage<?> message) {
		try {
			session.sendMessage(message);
		} catch (IOException | RuntimeException ex) {
			// RuntimeException: SessionLimitExceededException (a receiver too slow, now closed) or a closed session.
			log.warn("Failed to send WebSocket message to {}: {}", playerUuid, ex.toString());
		}
	}
}
