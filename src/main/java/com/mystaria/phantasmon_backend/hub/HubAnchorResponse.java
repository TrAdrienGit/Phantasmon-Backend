package com.mystaria.phantasmon_backend.hub;

import java.time.Instant;
import java.util.UUID;

/** A Hub Anchor as the API shows it, with its hub's name and size (width x, height y, length z; D-35). */
public record HubAnchorResponse(
		UUID uuid,
		UUID ownerUuid,
		String hub,
		String name,
		String serverFingerprint,
		String dimension,
		Origin origin,
		int yaw,
		Size size,
		Instant createdAt) {

	public record Origin(double x, double y, double z) {
	}

	public record Size(int x, int y, int z) {
	}

	static HubAnchorResponse from(HubAnchor anchor, Hub hub) {
		return new HubAnchorResponse(anchor.getUuid(), anchor.getOwnerUuid(), hub.getName(), anchor.getName(),
				anchor.getServerFingerprint(), anchor.getDimension(),
				new Origin(anchor.getOriginX(), anchor.getOriginY(), anchor.getOriginZ()),
				anchor.getYaw(), new Size(hub.getSizeX(), hub.getSizeY(), hub.getSizeZ()), anchor.getCreatedAt());
	}
}
