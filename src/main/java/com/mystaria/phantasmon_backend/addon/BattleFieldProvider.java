package com.mystaria.phantasmon_backend.addon;

import java.util.List;

/**
 * Battle fields from an add-on (2026-11-18; Phantasmon Events' League Reborn: Pokémon Reborn's field effects). A lobby
 * may then add a field to any format: the battle runs with the Showdown rule {@link #rule(String)}, which the add-on's
 * client mod installs in the engine. Who may pick a field: the holders of a {@code FIELD} reward for it (a gym leader
 * and their gym's field), and admins. An event NPC may impose one. Without a provider, there are no fields.
 */
public interface BattleFieldProvider {

	/** The fields, in the order the lobby lists them. */
	List<String> fieldIds();

	default boolean exists(String fieldId) {
		return fieldId != null && fieldIds().contains(fieldId);
	}

	/** The Showdown rule that lays the field down, added to the format's rules. */
	default String rule(String fieldId) {
		return "League Reborn = " + fieldId;
	}
}
