package com.mystaria.phantasmon_backend.pokemon;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Matches the OpenAPI {@code PokemonCreateRequest} schema. {@code uuid} and
 * {@code owner_uuid} are deliberately absent: identity is server-generated,
 * ownership comes from the authenticated JWT — never from client input.
 */
public record PokemonCreateRequest(
		@NotNull UUID requestUuid,
		@NotBlank String species,
		String form,
		@NotNull @Min(1) @Max(100) Integer level,
		@NotBlank String nature,
		@NotBlank String ability,
		Boolean isShiny,
		@Min(1) @Max(16) Integer boxId,
		@Min(1) @Max(36) Integer boxSlot,
		@Min(1) @Max(6) Integer teamSlot,
		@NotBlank String cobblemonDataVersion,
		@NotNull Map<String, Object> data) {
}
