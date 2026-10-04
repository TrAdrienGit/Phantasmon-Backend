package com.mystaria.phantasmon_backend.pokemon;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Matches the OpenAPI {@code PokemonCreateRequest} schema. {@code uuid} and
 * {@code owner_uuid} are deliberately absent: identity is server-generated,
 * ownership comes from the authenticated JWT — never from client input.
 */
public record PokemonCreateRequest(
		@NotNull UUID requestUuid,
		@NotBlank @Size(max = 64) String species,
		@Size(max = 64) String form,
		@NotNull @Min(1) @Max(100) Integer level,
		@NotBlank @Size(max = 32) String nature,
		@NotBlank @Size(max = 64) String ability,
		Boolean isShiny,
		@Min(1) @Max(16) Integer boxId,
		@Min(1) @Max(30) Integer boxSlot,
		@Min(1) @Max(6) Integer teamSlot,
		@NotBlank @Size(max = 32) String cobblemonDataVersion,
		@NotNull Map<String, Object> data) {
}
