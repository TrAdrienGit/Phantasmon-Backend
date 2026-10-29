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

	/** A mirror hub spans the whole height of the world (D-38): y {@value #MIRROR_MIN_Y} to 319. */
	public static final int MIRROR_MIN_Y = -64;
	public static final int MIRROR_HEIGHT = 384;

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

	/** A mirror hub (D-38): its terrain is copied from its source anchor's zone, it never has a build. */
	@Column(nullable = false)
	private boolean mirror;

	/** A mirror hub's radius (D-38): a square of side {@code 2 * radius + 1}; null for an ordinary hub. */
	private Short radius;

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

	/** A mirror hub (D-38) of that radius. */
	public static Hub mirror(UUID uuid, String name, int radius, UUID createdBy) {
		Hub hub = new Hub(uuid, name, 2 * radius + 1, MIRROR_HEIGHT, 2 * radius + 1, createdBy);
		hub.mirror = true;
		hub.radius = (short) radius;
		return hub;
	}

	/** A mirror hub's new radius: it grows or shrinks around the same centre. */
	public void resize(int newRadius) {
		radius = (short) newRadius;
		sizeX = (short) (2 * newRadius + 1);
		sizeZ = sizeX;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
