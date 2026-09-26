package com.mystaria.phantasmon_backend.battle;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Matches {@code POST /battles/{uuid}/result}. */
public record SubmitBattleResultRequest(
		@NotNull UUID winnerUuid,
		Map<String, Object> log) {
}
