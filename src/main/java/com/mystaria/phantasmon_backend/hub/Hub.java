package com.mystaria.phantasmon_backend.hub;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A hub (D-35): a separate social space players enter through its anchors, created by an admin with its own size —
 * {@code sizeX} wide, {@code sizeY} high, {@code sizeZ} long (the anchor's front is +Z) — and its own build in
 * {@code hub_schematics/hub_<name>/}. The hub "global" comes from the single Global Hub of before.
 */
@Entity
@Table(name = "hubs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Hub {

	@Id
	private UUID uuid;

	@Column(nullable = false, length = 32)
	private String name;

	@Column(name = "size_x", nullable = false)
	private short sizeX;

	@Column(name = "size_y", nullable = false)
	private short sizeY;

	@Column(name = "size_z", nullable = false)
	private short sizeZ;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Hub(UUID uuid, String name, int sizeX, int sizeY, int sizeZ, UUID createdBy) {
		this.uuid = uuid;
		this.name = name;
		this.sizeX = (short) sizeX;
		this.sizeY = (short) sizeY;
		this.sizeZ = (short) sizeZ;
		this.createdBy = createdBy;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
