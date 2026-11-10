package com.mystaria.phantasmon_backend.hub;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A Hub Anchor as the API shows it, with its hub's name and size (width x, height y, length z; D-35), its role, its hub's
 * {@code kind} and the fields that kind adds ({@code extras}, at the top level; mirror hubs: {@code mirror: true}, D-38).
 */
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
		String role,
		String kind,
		Instant createdAt,
		@JsonIgnore Map<String, Object> extras) {

	@JsonAnyGetter
	public Map<String, Object> extraFields() {
		return extras;
	}

	public record Origin(double x, double y, double z) {
	}

	public record Size(int x, int y, int z) {
	}

	static HubAnchorResponse from(HubAnchor anchor, Hub hub, Map<String, Object> extras) {
		return new HubAnchorResponse(anchor.getUuid(), anchor.getOwnerUuid(), hub.getName(), anchor.getName(),
				anchor.getServerFingerprint(), anchor.getDimension(),
				new Origin(anchor.getOriginX(), anchor.getOriginY(), anchor.getOriginZ()),
				anchor.getYaw(), new Size(hub.getSizeX(), hub.getSizeY(), hub.getSizeZ()), anchor.getRole(), hub.getKind(),
				anchor.getCreatedAt(), extras);
	}
}
