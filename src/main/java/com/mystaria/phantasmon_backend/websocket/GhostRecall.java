package com.mystaria.phantasmon_backend.websocket;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import com.mystaria.phantasmon_backend.presence.PresenceService;

import lombok.extern.slf4j.Slf4j;

/**
 * Takes a player's Ghost back in (CAD Partie 2 §7/§8): clears it from the presence and broadcasts
 * {@code GhostEntityDespawn} to the player's group <b>and</b> the player (same audience as the spawn). Shared by
 * {@code RecallGhost}, a live trade that gives the Ghost away, and the start of a Ghost battle (TODO-14).
 */
@Component
@Slf4j
public class GhostRecall {

	private final PresenceService presenceService;
	private final SessionRegistry sessionRegistry;

	public GhostRecall(PresenceService presenceService, SessionRegistry sessionRegistry) {
		this.presenceService = presenceService;
		this.sessionRegistry = sessionRegistry;
	}

	/** Recalls whatever Ghost the player has out; no-op if none. */
	public void recall(UUID playerUuid, String reason) {
		recallIf(playerUuid, pokemonUuid -> true, reason);
	}

	/** Recalls the player's Ghost only if it is the one {@code which} accepts (e.g. the Pokémon just traded away). */
	public void recallIf(UUID playerUuid, Predicate<UUID> which, String reason) {
		presenceService.find(playerUuid)
				.map(presence -> presence.activeGhostPokemonUuid())
				.filter(which)
				.ifPresent(pokemonUuid -> {
					List<UUID> groupMembers = presenceService.groupMembers(playerUuid);
					presenceService.recallGhost(playerUuid);
					log.info("Player {}'s Ghost {} recalled ({})", playerUuid, pokemonUuid, reason);
					WsMessage despawn = WsMessage.of("GhostEntityDespawn",
							Map.of("player_uuid", playerUuid, "pokemon_uuid", pokemonUuid));
					groupMembers.forEach(member -> sessionRegistry.send(member, despawn));
					sessionRegistry.send(playerUuid, despawn);
				});
	}
}
