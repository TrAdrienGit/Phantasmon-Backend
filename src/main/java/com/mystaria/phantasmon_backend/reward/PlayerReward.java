package com.mystaria.phantasmon_backend.reward;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A look an admin gave a player (TODO-37): a {@code FILTER} or an {@code AURA} (its {@code value} names it) or the right
 * to resize ({@code SCALE}), for all their Pokémon ({@code species} null) or one species (forms included).
 */
@Entity
@Table(name = "player_rewards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlayerReward {

	public static final String FILTER = "FILTER";
	public static final String AURA = "AURA";
	public static final String SCALE = "SCALE";

	@Id
	private UUID uuid;

	@Column(name = "player_uuid", nullable = false)
	private UUID playerUuid;

	@Column(nullable = false, length = 16)
	private String kind;

	@Column(nullable = false, length = 32)
	private String value;

	@Column(length = 64)
	private String species;

	@Column(length = 100)
	private String note;

	@Column(name = "granted_by")
	private UUID grantedBy;

	@Column(name = "granted_at", nullable = false)
	private Instant grantedAt;

	public PlayerReward(UUID uuid, UUID playerUuid, String kind, String value, String species, String note, UUID grantedBy,
			Instant grantedAt) {
		this.uuid = uuid;
		this.playerUuid = playerUuid;
		this.kind = kind;
		this.value = value;
		this.species = species;
		this.note = note;
		this.grantedBy = grantedBy;
		this.grantedAt = grantedAt;
	}

	/** Whether it covers a Pokémon of {@code pokemonSpecies}. */
	public boolean covers(String pokemonSpecies) {
		return species == null || species.equalsIgnoreCase(pokemonSpecies);
	}
}
