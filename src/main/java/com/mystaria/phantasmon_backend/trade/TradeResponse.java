package com.mystaria.phantasmon_backend.trade;

import java.util.UUID;

/** Matches the OpenAPI {@code Trade} schema. */
public record TradeResponse(
		UUID uuid,
		UUID initiatorUuid,
		UUID recipientUuid,
		UUID offeredPokemon,
		UUID requestedPokemon,
		String status) {

	static TradeResponse from(Trade trade) {
		return new TradeResponse(
				trade.getUuid(),
				trade.getInitiatorUuid(),
				trade.getRecipientUuid(),
				trade.getOfferedPokemon(),
				trade.getRequestedPokemon(),
				trade.getStatus().name());
	}
}
