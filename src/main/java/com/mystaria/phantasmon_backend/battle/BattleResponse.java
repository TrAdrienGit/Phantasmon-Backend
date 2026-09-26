package com.mystaria.phantasmon_backend.battle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Matches the OpenAPI {@code BattleSession} schema, extended with team snapshots and timestamps. */
public record BattleResponse(
		UUID uuid,
		UUID playerA,
		UUID playerB,
		List<UUID> teamA,
		List<UUID> teamB,
		String status,
		Map<String, Object> result,
		Instant createdAt,
		Instant finishedAt) {

	static BattleResponse from(BattleSession battle) {
		return new BattleResponse(
				battle.getUuid(),
				battle.getPlayerA(),
				battle.getPlayerB(),
				battle.getTeamA(),
				battle.getTeamB(),
				battle.getStatus().name(),
				battle.getResult(),
				battle.getCreatedAt(),
				battle.getFinishedAt());
	}
}
