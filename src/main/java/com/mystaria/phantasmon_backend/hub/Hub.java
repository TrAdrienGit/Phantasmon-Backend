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

	/** An ordinary hub: anyone's anchors, one per player, built from its schematic (D-34, D-35). */
	public static final String KIND_NORMAL = "NORMAL";
	/** The world's height: y {@value #WORLD_MIN_Y} to 319. */
	public static final int WORLD_MIN_Y = -64;
	public static final int WORLD_HEIGHT = 384;

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

	/**
	 * {@link #KIND_NORMAL}, or a kind an add-on defines ({@link com.mystaria.phantasmon_backend.addon.HubKind}; Phantasmon
	 * Events' {@code MIRROR} hubs, D-38). D-41: the core knows nothing else of the other kinds.
	 */
	@Column(nullable = false, length = 16)
	private String kind = KIND_NORMAL;

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

	/** A hub of an add-on's kind ({@link com.mystaria.phantasmon_backend.addon.HubKind}). */
	public Hub(UUID uuid, String name, String kind, int sizeX, int sizeY, int sizeZ, UUID createdBy) {
		this(uuid, name, sizeX, sizeY, sizeZ, createdBy);
		this.kind = kind;
	}

	public boolean isNormal() {
		return KIND_NORMAL.equals(kind);
	}

	/** A new size (an add-on's kind, e.g. a mirror hub's radius changed). */
	public void resize(int sizeX, int sizeY, int sizeZ) {
		this.sizeX = (short) sizeX;
		this.sizeY = (short) sizeY;
		this.sizeZ = (short) sizeZ;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
