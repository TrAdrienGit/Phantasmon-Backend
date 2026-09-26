package com.mystaria.phantasmon_backend.battle;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Matches {@code POST /battles}. {@code request_uuid} is required per
 * CONTEXT_CURSOR_BACKEND.md rule 3 (idempotent sensitive POSTs) even though
 * the OpenAPI draft omits it for this endpoint — same precedent as
 * {@code ProposeTradeRequest}. {@code team} is the initiator's own team
 * (ownership re-verified server-side); the opponent's team is never taken
 * from the request — it is derived from their own active team in the DB, so
 * one player can never dictate what the other is battling with.
 */
public record CreateBattleRequest(
		@NotNull UUID requestUuid,
		@NotNull UUID opponentUuid,
		@NotEmpty List<UUID> team) {
}
