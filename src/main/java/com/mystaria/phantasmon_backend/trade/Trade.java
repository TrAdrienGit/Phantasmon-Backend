package com.mystaria.phantasmon_backend.trade;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An atomic Pokémon exchange between two players (CAD Partie 3 §D). */
@Entity
@Table(name = "trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trade {

	@Id
	private UUID uuid;

	@Column(name = "initiator_uuid", nullable = false)
	private UUID initiatorUuid;

	@Column(name = "recipient_uuid", nullable = false)
	private UUID recipientUuid;

	@Column(name = "offered_pokemon", nullable = false)
	private UUID offeredPokemon;

	@Column(name = "requested_pokemon", nullable = false)
	private UUID requestedPokemon;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private TradeStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Setter
	@Column(name = "resolved_at")
	private Instant resolvedAt;

	public Trade(UUID uuid, UUID initiatorUuid, UUID recipientUuid, UUID offeredPokemon, UUID requestedPokemon) {
		this.uuid = uuid;
		this.initiatorUuid = initiatorUuid;
		this.recipientUuid = recipientUuid;
		this.offeredPokemon = offeredPokemon;
		this.requestedPokemon = requestedPokemon;
		this.status = TradeStatus.PENDING;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
