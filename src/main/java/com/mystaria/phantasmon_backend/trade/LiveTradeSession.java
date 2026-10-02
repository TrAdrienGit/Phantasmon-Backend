package com.mystaria.phantasmon_backend.trade;

import java.util.UUID;

/**
 * In-memory state of one live trade between two players, both on the trade
 * screen at once (Adrien 2026-10-02). Deliberately not persisted — like
 * {@code PlayerPresence}, a negotiation in progress has no meaning once the
 * backend restarts; only the final, completed swap is written to
 * {@code trades}. Not thread-safe on its own: every access goes through
 * {@link LiveTradeService}'s lock.
 */
final class LiveTradeSession {

	private final UUID uuid;
	private final UUID initiatorUuid;
	private final String initiatorName;
	private final UUID recipientUuid;
	private final String recipientName;

	private UUID initiatorOffer;
	private UUID recipientOffer;
	private boolean initiatorReady;
	private boolean recipientReady;

	LiveTradeSession(UUID uuid, UUID initiatorUuid, String initiatorName, UUID recipientUuid, String recipientName) {
		this.uuid = uuid;
		this.initiatorUuid = initiatorUuid;
		this.initiatorName = initiatorName;
		this.recipientUuid = recipientUuid;
		this.recipientName = recipientName;
	}

	UUID uuid() {
		return uuid;
	}

	UUID initiatorUuid() {
		return initiatorUuid;
	}

	UUID recipientUuid() {
		return recipientUuid;
	}

	UUID partnerOf(UUID playerUuid) {
		return isInitiator(playerUuid) ? recipientUuid : initiatorUuid;
	}

	String nameOf(UUID playerUuid) {
		return isInitiator(playerUuid) ? initiatorName : recipientName;
	}

	UUID offerOf(UUID playerUuid) {
		return isInitiator(playerUuid) ? initiatorOffer : recipientOffer;
	}

	boolean isReady(UUID playerUuid) {
		return isInitiator(playerUuid) ? initiatorReady : recipientReady;
	}

	/** Any offer change, from either side, cancels both players' ready state (spec §7.3 rule 3). */
	void setOffer(UUID playerUuid, UUID pokemonUuid) {
		if (isInitiator(playerUuid)) {
			initiatorOffer = pokemonUuid;
		} else {
			recipientOffer = pokemonUuid;
		}
		initiatorReady = false;
		recipientReady = false;
	}

	void setReady(UUID playerUuid, boolean ready) {
		if (isInitiator(playerUuid)) {
			initiatorReady = ready;
		} else {
			recipientReady = ready;
		}
	}

	boolean bothOffersChosen() {
		return initiatorOffer != null && recipientOffer != null;
	}

	boolean bothReady() {
		return initiatorReady && recipientReady;
	}

	private boolean isInitiator(UUID playerUuid) {
		return initiatorUuid.equals(playerUuid);
	}
}
