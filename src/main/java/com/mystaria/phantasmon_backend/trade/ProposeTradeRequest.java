package com.mystaria.phantasmon_backend.trade;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Matches {@code POST /trades}. {@code request_uuid} is required per
 * CONTEXT_CURSOR_BACKEND.md rule 3 (idempotent sensitive POSTs) even though
 * the OpenAPI draft omits it for this endpoint — CONTEXT outranks OpenAPI in
 * the documented spec hierarchy.
 */
public record ProposeTradeRequest(
		@NotNull UUID requestUuid,
		@NotNull UUID recipientUuid,
		@NotNull UUID offeredPokemonUuid,
		@NotNull UUID requestedPokemonUuid) {
}
