package com.mystaria.phantasmon_backend.addon;

import java.util.Map;
import java.util.UUID;

/**
 * WebSocket messages of an add-on (TODO-35): every type the core does not know is offered to these handlers, in
 * order; none handles it: {@code ERROR_WS_UNKNOWN_MESSAGE_TYPE} as before. Prefix the types with the add-on's name
 * ({@code Events…}).
 */
public interface WsMessageHandler {

	/** Whether this handler takes that message type. */
	boolean handles(String type);

	/** A message of one of its types, from an authenticated player. */
	void handle(UUID playerUuid, String type, Map<String, Object> data) throws Exception;

	/** The player's WebSocket closed (not when a newer connection of the same player replaced it). */
	default void onDisconnect(UUID playerUuid) {
	}
}
