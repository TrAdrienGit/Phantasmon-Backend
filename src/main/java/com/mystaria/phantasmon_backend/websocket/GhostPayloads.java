package com.mystaria.phantasmon_backend.websocket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.presence.Position;

/**
 * The rendering data of a sent-out Ghost, shared by {@code GhostEntitySpawn} (server group) and {@code HubGhostSpawn}
 * (Global Hub). It carries the Pokémon's rendering identifiers (species/form/shiny/level/gender/nickname) because the
 * receiving client has no way to look up someone else's Pokémon over REST — ownership-gated routes only expose the
 * caller's own.
 */
public final class GhostPayloads {

	private GhostPayloads() {
	}

	/**
	 * With the owner's real {@code position} (server group only). It may still be null right after
	 * {@code SendOutGhost} if the player hasn't sent a {@code PositionUpdate} yet — {@code Map.of} would NPE on that,
	 * hence the plain map.
	 */
	public static Map<String, Object> withPosition(UUID playerUuid, Pokemon pokemon, Position position) {
		Map<String, Object> data = withoutPosition(playerUuid, pokemon);
		data.put("position", position);
		return data;
	}

	/** For the Global Hub: no {@code position} key at all — real coordinates never leave the player's server group. */
	public static Map<String, Object> withoutPosition(UUID playerUuid, Pokemon pokemon) {
		Map<String, Object> data = new HashMap<>();
		data.put("player_uuid", playerUuid);
		data.put("pokemon_uuid", pokemon.getUuid());
		data.put("species", pokemon.getSpecies());
		data.put("form", pokemon.getForm());
		data.put("is_shiny", pokemon.isShiny());
		data.put("level", pokemon.getLevel());
		// "M"/"F" as stored by the editor/Showdown import, null when not set — some models differ by gender
		// (Meowstic, Pikachu...) and the receiving client can't look it up either.
		data.put("gender", pokemon.getData() == null ? null : pokemon.getData().get("gender"));
		// Shown as "[Ghost] <nickname>" above the Ghost (CAD Partie 1 §5); null when the Pokémon has none.
		data.put("nickname", pokemon.getData() == null ? null : pokemon.getData().get("nickname"));
		return data;
	}
}
