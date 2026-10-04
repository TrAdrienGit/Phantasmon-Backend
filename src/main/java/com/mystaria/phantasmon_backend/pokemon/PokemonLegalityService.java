package com.mystaria.phantasmon_backend.pokemon;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.common.ApiException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enforces IV/EV legality on the {@code data} JSONB payload of a Pokémon,
 * called explicitly on create/update/import — never only via a Bean
 * Validation annotation (agents/backend-agent-context.md rule 2).
 *
 * <p><b>Scope limitation, deliberate</b>: moveset/ability-vs-species
 * consistency (CAD Partie 3 §B) is <em>not</em> checked here. That check
 * needs real Cobblemon species/ability/move data, which only exists on the
 * client (this backend has zero Minecraft/Fabric dependency by design — see
 * reference/database-schema.md §4 on `ability`). Only structurally-checkable
 * rules (IV/EV ranges) are enforced server-side in V1.
 *
 * <p><b>Bounds</b> (security audit 2026-10-04): {@code data} is free JSON stored for up to 480 Pokémon per player
 * and the nickname is shown above the Ghost to every other player, so both are capped (SEC-5); malformed stats are
 * refused with a business error instead of escaping as a 500 (SEC-9).
 */
@Service
public class PokemonLegalityService {

	private static final int IV_MIN = 0;
	private static final int IV_MAX = 31;
	private static final int EV_MIN = 0;
	private static final int EV_MAX = 252;
	private static final int EV_TOTAL_MAX = 510;
	/** Same limit as the client's editor field. */
	static final int NICKNAME_MAX_LENGTH = 20;
	/** A full set (IVs, EVs, 4 moves, item, Tera, nickname...) is well under 1 KiB. */
	static final int DATA_MAX_BYTES = 16 * 1024;

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	public void validate(Map<String, Object> data) {
		if (JSON.writeValueAsString(data).getBytes(StandardCharsets.UTF_8).length > DATA_MAX_BYTES) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_POKEMON_DATA_TOO_LARGE",
					Map.of("max_bytes", DATA_MAX_BYTES));
		}
		Object nickname = data.get("nickname");
		if (nickname != null) {
			if (!(nickname instanceof String text)) {
				throw invalid("nickname");
			}
			if (text.length() > NICKNAME_MAX_LENGTH) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_NICKNAME_TOO_LONG",
						Map.of("max", NICKNAME_MAX_LENGTH));
			}
		}

		for (Map.Entry<?, ?> entry : statMap(data, "ivs").entrySet()) {
			int value = toInt(entry.getValue(), "ivs");
			if (value < IV_MIN || value > IV_MAX) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_IV_OUT_OF_RANGE",
						Map.of("stat", String.valueOf(entry.getKey()), "value", value, "min", IV_MIN, "max", IV_MAX));
			}
		}
		int total = 0;
		for (Map.Entry<?, ?> entry : statMap(data, "evs").entrySet()) {
			int value = toInt(entry.getValue(), "evs");
			if (value < EV_MIN || value > EV_MAX) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_EV_OUT_OF_RANGE",
						Map.of("stat", String.valueOf(entry.getKey()), "value", value, "min", EV_MIN, "max", EV_MAX));
			}
			total += value;
		}
		if (total > EV_TOTAL_MAX) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_EV_TOTAL_EXCEEDED",
					Map.of("total", total, "max", EV_TOTAL_MAX));
		}
	}

	private static Map<?, ?> statMap(Map<String, Object> data, String field) {
		Object value = data.getOrDefault(field, Map.of());
		if (!(value instanceof Map<?, ?> map)) {
			throw invalid(field);
		}
		return map;
	}

	private static int toInt(Object value, String field) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException ex) {
			throw invalid(field);
		}
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_LEGALITY_INVALID_DATA", Map.of("field", field));
	}
}
