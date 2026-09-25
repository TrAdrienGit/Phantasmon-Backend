package com.mystaria.phantasmon_backend.websocket;

import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mystaria.phantasmon_backend.presence.PresenceService;

import lombok.extern.slf4j.Slf4j;

/**
 * Sweeps expired presences (CAD Partie 3 §F) and force-closes their
 * WebSocket session if still technically open (a dead/unresponsive
 * connection whose heartbeat stopped arriving).
 */
@Component
@Slf4j
public class PresenceTtlSweeper {

	private final PresenceService presenceService;
	private final SessionRegistry sessionRegistry;

	public PresenceTtlSweeper(PresenceService presenceService, SessionRegistry sessionRegistry) {
		this.presenceService = presenceService;
		this.sessionRegistry = sessionRegistry;
	}

	@Scheduled(fixedRateString = "${phantasmon.presence.sweep-interval-ms}")
	public void sweep() {
		List<UUID> expired = presenceService.cleanupExpired();
		for (UUID playerUuid : expired) {
			log.info("Presence expired (missed heartbeat): {}", playerUuid);
			sessionRegistry.close(playerUuid);
		}
	}
}
