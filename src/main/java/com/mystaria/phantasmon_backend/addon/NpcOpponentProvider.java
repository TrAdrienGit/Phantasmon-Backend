package com.mystaria.phantasmon_backend.addon;

import java.util.List;
import java.util.UUID;

import com.mystaria.phantasmon_backend.pokemon.PokemonResponse;

/**
 * Trainer NPCs a player can battle ({@code BattleNpcStart}), from an add-on (TODO-35; Phantasmon Events' event NPCs).
 * Without one, {@code BattleNpcStart} answers {@code ERROR_NPC_NOT_FOUND}.
 */
public interface NpcOpponentProvider {

	/** The NPC {@code npcUuid} as {@code playerUuid} may battle it now; null if it does not exist or is closed to them. */
	NpcOpponent npcOpponent(UUID playerUuid, UUID npcUuid);

	/**
	 * An NPC as a battle needs it: its team (already loaded), its AI level as the client knows it ({@code random},
	 * {@code expert}…), the AI's chat debug, its format and its skin (null: the default one). {@code intro} and
	 * {@code ambiance}: the Showtime intro and ambiance its battles impose (2026-11-18), the NPC starring in the intro;
	 * null: the player picks them in the lobby as against a player.
	 */
	record NpcOpponent(UUID uuid, String name, String skin, String ai, boolean aiDebug, String formatId,
			List<PokemonResponse> team, String intro, String ambiance) {

		public NpcOpponent(UUID uuid, String name, String skin, String ai, boolean aiDebug, String formatId,
				List<PokemonResponse> team) {
			this(uuid, name, skin, ai, aiDebug, formatId, team, null, null);
		}
	}
}
