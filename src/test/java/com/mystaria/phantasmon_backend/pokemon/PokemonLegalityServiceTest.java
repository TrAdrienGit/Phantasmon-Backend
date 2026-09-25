package com.mystaria.phantasmon_backend.pokemon;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mystaria.phantasmon_backend.common.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PokemonLegalityServiceTest {

	private final PokemonLegalityService service = new PokemonLegalityService();

	private Map<String, Object> validData() {
		return Map.of(
				"ivs", Map.of("hp", 31, "atk", 31, "def", 31, "spa", 31, "spd", 31, "spe", 31),
				"evs", Map.of("hp", 0, "atk", 252, "def", 0, "spa", 0, "spd", 4, "spe", 252),
				"moves", List.of("tackle"));
	}

	@Test
	void acceptsLegalIvsAndEvs() {
		assertThatCode(() -> service.validate(validData())).doesNotThrowAnyException();
	}

	@Test
	void rejectsIvAboveThirtyOne() {
		Map<String, Object> data = Map.of(
				"ivs", Map.of("hp", 32, "atk", 0, "def", 0, "spa", 0, "spd", 0, "spe", 0),
				"evs", Map.of());

		assertThatThrownBy(() -> service.validate(data))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("ERROR_LEGALITY_IV_OUT_OF_RANGE"));
	}

	@Test
	void rejectsNegativeIv() {
		Map<String, Object> data = Map.of(
				"ivs", Map.of("hp", -1, "atk", 0, "def", 0, "spa", 0, "spd", 0, "spe", 0),
				"evs", Map.of());

		assertThatThrownBy(() -> service.validate(data))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("ERROR_LEGALITY_IV_OUT_OF_RANGE"));
	}

	@Test
	void rejectsEvAboveTwoHundredFiftyTwo() {
		Map<String, Object> data = Map.of(
				"ivs", Map.of(),
				"evs", Map.of("hp", 253, "atk", 0, "def", 0, "spa", 0, "spd", 0, "spe", 0));

		assertThatThrownBy(() -> service.validate(data))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("ERROR_LEGALITY_EV_OUT_OF_RANGE"));
	}

	@Test
	void rejectsEvTotalAboveFiveHundredTen() {
		Map<String, Object> data = Map.of(
				"ivs", Map.of(),
				"evs", Map.of("hp", 252, "atk", 252, "def", 10, "spa", 0, "spd", 0, "spe", 0));

		assertThatThrownBy(() -> service.validate(data))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("ERROR_LEGALITY_EV_TOTAL_EXCEEDED"));
	}

	@Test
	void treatsMissingIvsOrEvsAsAllZero() {
		Map<String, Object> data = Map.of();

		assertThatCode(() -> service.validate(data)).doesNotThrowAnyException();
	}
}
