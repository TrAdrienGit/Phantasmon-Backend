package com.mystaria.phantasmon_backend.pokemon;

import java.util.Map;
import java.util.UUID;

/** Matches the OpenAPI {@code Pokemon} schema. */
public record PokemonResponse(
		UUID uuid,
		UUID ownerUuid,
		String species,
		String form,
		int level,
		String nature,
		String ability,
		boolean isShiny,
		Integer boxId,
		Integer boxSlot,
		Integer teamSlot,
		String cobblemonDataVersion,
		Map<String, Object> data) {

	static PokemonResponse from(Pokemon pokemon) {
		return new PokemonResponse(
				pokemon.getUuid(),
				pokemon.getOwnerUuid(),
				pokemon.getSpecies(),
				pokemon.getForm(),
				pokemon.getLevel(),
				pokemon.getNature(),
				pokemon.getAbility(),
				pokemon.isShiny(),
				pokemon.getBoxId() == null ? null : pokemon.getBoxId().intValue(),
				pokemon.getBoxSlot() == null ? null : pokemon.getBoxSlot().intValue(),
				pokemon.getTeamSlot() == null ? null : pokemon.getTeamSlot().intValue(),
				pokemon.getCobblemonDataVersion(),
				pokemon.getData());
	}
}
