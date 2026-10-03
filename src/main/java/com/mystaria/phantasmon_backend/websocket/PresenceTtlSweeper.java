package com.mystaria.phantasmon_backend.websocket;

import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mystaria.phantasmon_backend.presence.PresenceService;

import lombok.extern.slf4j.Slf4j;

/**
 * Sweeps expired presences (CAD Partie 3 §F): each expired player leaves its
 * group (its Ghost is despawned for the others) and its WebSocket session is
 * force-closed if still technically open (a dead/unresponsive connection whose
 * heartbeat stopped arriving). See {@link PhantasmonWebSocketHandler#expire}.
 */
@Component
@Slf4j
public class PresenceTtlSweeper {

	private final PresenceService presenceService;
	private final PhantasmonWebSocketHandler webSocketHandler;

	public PresenceTtlSweeper(PresenceService presenceService, PhantasmonWebSocketHandler webSocketHandler) {
		this.presenceService = presenceService;
		this.webSocketHandler = webSocketHandler;
	}

	@Scheduled(fixedRateString = "${phantasmon.presence.sweep-interval-ms}")
	public void sweep() {
		List<UUID> expired = presenceService.findExpired();
		for (UUID playerUuid : expired) {
			log.info("Presence expired (missed heartbeat): {}", playerUuid);
			webSocketHandler.expire(playerUuid);
		}
	}
}
