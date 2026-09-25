package com.mystaria.phantasmon_backend.pokemon;

import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Matches the OpenAPI {@code PokemonUpdateRequest} schema — partial update. */
public record PokemonUpdateRequest(
		Map<String, Object> data,
		@Min(1) @Max(100) Integer level,
		@Min(1) @Max(6) Integer teamSlot) {
}
