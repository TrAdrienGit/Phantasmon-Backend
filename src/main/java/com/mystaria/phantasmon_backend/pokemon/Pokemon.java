package com.mystaria.phantasmon_backend.pokemon;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A Ghost Pokémon (CAD Partie 1 §3, PHANTASMON_DB_SCHEMA.md §4). Only
 * Cobblemon **identifiers** are stored (species/form/ability/moves as plain
 * strings) — never base stats, models, or animations, which stay resolved
 * client-side.
 */
@Entity
@Table(name = "pokemon")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pokemon {

	@Id
	private UUID uuid;

	@Column(name = "owner_uuid", nullable = false)
	@Setter
	private UUID ownerUuid;

	@Setter
	@Column(nullable = false, length = 64)
	private String species;

	@Setter
	@Column(length = 64)
	private String form;

	@Setter
	@Column(nullable = false)
	private short level;

	@Setter
	@Column(nullable = false, length = 32)
	private String nature;

	@Setter
	@Column(nullable = false, length = 64)
	private String ability;

	@Setter
	@Column(name = "is_shiny", nullable = false)
	private boolean shiny;

	@Setter
	@Column(name = "box_id")
	private Short boxId;

	@Setter
	@Column(name = "box_slot")
	private Short boxSlot;

	@Setter
	@Column(name = "team_slot")
	private Short teamSlot;

	@Setter
	@Column(name = "cobblemon_data_version", nullable = false, length = 32)
	private String cobblemonDataVersion;

	@Setter
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, Object> data;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Pokemon(UUID uuid, UUID ownerUuid, String species, String form, short level, String nature,
			String ability, boolean shiny, Short boxId, Short boxSlot, Short teamSlot,
			String cobblemonDataVersion, Map<String, Object> data) {
		this.uuid = uuid;
		this.ownerUuid = ownerUuid;
		this.species = species;
		this.form = form;
		this.level = level;
		this.nature = nature;
		this.ability = ability;
		this.shiny = shiny;
		this.boxId = boxId;
		this.boxSlot = boxSlot;
		this.teamSlot = teamSlot;
		this.cobblemonDataVersion = cobblemonDataVersion;
		this.data = data;
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}
}
