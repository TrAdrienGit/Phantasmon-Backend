package com.mystaria.phantasmon_backend.hub;

import java.time.Instant;
import java.util.UUID;

/** A Hub Anchor as the API shows it; {@code size} is the cube's edge in blocks, the same for every anchor. */
public record HubAnchorResponse(
		UUID uuid,
		UUID ownerUuid,
		String name,
		String serverFingerprint,
		String dimension,
		Origin origin,
		int yaw,
		int size,
		Instant createdAt) {

	public record Origin(double x, double y, double z) {
	}

	static HubAnchorResponse from(HubAnchor anchor, int size) {
		return new HubAnchorResponse(anchor.getUuid(), anchor.getOwnerUuid(), anchor.getName(),
				anchor.getServerFingerprint(), anchor.getDimension(),
				new Origin(anchor.getOriginX(), anchor.getOriginY(), anchor.getOriginZ()),
				anchor.getYaw(), size, anchor.getCreatedAt());
	}
}
