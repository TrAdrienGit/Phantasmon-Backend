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
 * A player's doorway to a hub on their Minecraft server (network-cahier-des-charges.md §5.2, D-35): a box of the hub's
 * size, centred on {@code origin} horizontally and rising from its feet, turned by {@code yaw}.
 */
@Entity
@Table(name = "hub_anchors")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HubAnchor {

	@Id
	private UUID uuid;

	@Column(name = "owner_uuid", nullable = false)
	private UUID ownerUuid;

	/** The hub this anchor leads to (D-35). */
	@Column(name = "hub_uuid", nullable = false)
	private UUID hubUuid;

	@Column(nullable = false, length = 32)
	private String name;

	@Column(name = "server_fingerprint", nullable = false, length = 128)
	private String serverFingerprint;

	@Column(nullable = false, length = 128)
	private String dimension;

	@Column(name = "origin_x", nullable = false)
	private double originX;

	@Column(name = "origin_y", nullable = false)
	private double originY;

	@Column(name = "origin_z", nullable = false)
	private double originZ;

	/** 0, 90, 180 or 270 degrees. */
	@Column(nullable = false)
	private short yaw;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public HubAnchor(UUID uuid, UUID ownerUuid, UUID hubUuid, String name, String serverFingerprint, String dimension,
			double originX, double originY, double originZ, short yaw) {
		this.uuid = uuid;
		this.ownerUuid = ownerUuid;
		this.hubUuid = hubUuid;
		this.name = name;
		this.serverFingerprint = serverFingerprint;
		this.dimension = dimension;
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		this.yaw = yaw;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
