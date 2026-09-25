package com.mystaria.phantasmon_backend.websocket;

import java.util.Map;

/** Generic envelope for every C2S/S2C WebSocket message (CAD Partie 2 §11). */
public record WsMessage(String type, Map<String, Object> data) {

	public static WsMessage of(String type, Map<String, Object> data) {
		return new WsMessage(type, data);
	}

	public static WsMessage error(String errorCode, Map<String, Object> details) {
		return new WsMessage("Error", Map.of("error_code", errorCode, "details", details));
	}
}
