package com.mystaria.phantasmon_backend.pokemon;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * Enforces IV/EV legality on the {@code data} JSONB payload of a Pokémon,
 * called explicitly on create/update/import — never only via a Bean
 * Validation annotation (CONTEXT_CURSOR_BACKEND.md rule 2).
 *
 * <p><b>Scope limitation, deliberate</b>: moveset/ability-vs-species
 * consistency (CAD Partie 3 §B) is <em>not</em> checked here. That check
 * needs real Cobblemon species/ability/move data, which only exists on the
 * client (this backend has zero Minecraft/Fabric dependency by design — see
 * PHANTASMON_DB_SCHEMA.md §4.3 on `ability`). Only structurally-checkable
 * rules (IV/EV ranges) are enforced server-side in V1.
 */
@Service
public class PokemonLegalityService {

	private static final int IV_MIN = 0;
	private static final int IV_MAX = 31;
	private static final int EV_MIN = 0;
	private static final int EV_MAX = 252;
	private static final int EV_TOTAL_MAX = 510;

	@SuppressWarnings("unchecked")
	public void validate(Map<String, Object> data) {
		Map<String, Object> ivs = (Map<String, Object>) data.getOrDefault("ivs", Map.of());
		for (Map.Entry<String, Object> entry : ivs.entrySet()) {
			int value = toInt(entry.getValue());
			if (value < IV_MIN || value > IV_MAX) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_IV_OUT_OF_RANGE",
						Map.of("stat", entry.getKey(), "value", value, "min", IV_MIN, "max", IV_MAX));
			}
		}

		Map<String, Object> evs = (Map<String, Object>) data.getOrDefault("evs", Map.of());
		int total = 0;
		for (Map.Entry<String, Object> entry : evs.entrySet()) {
			int value = toInt(entry.getValue());
			if (value < EV_MIN || value > EV_MAX) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_EV_OUT_OF_RANGE",
						Map.of("stat", entry.getKey(), "value", value, "min", EV_MIN, "max", EV_MAX));
			}
			total += value;
		}
		if (total > EV_TOTAL_MAX) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_EV_TOTAL_EXCEEDED",
					Map.of("total", total, "max", EV_TOTAL_MAX));
		}
	}

	private static int toInt(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		return Integer.parseInt(value.toString());
	}
}
