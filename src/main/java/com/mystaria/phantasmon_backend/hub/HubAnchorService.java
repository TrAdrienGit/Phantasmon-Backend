package com.mystaria.phantasmon_backend.hub;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.admin.AdminService;
import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * Hub Anchors (network-cahier-des-charges.md §5.2, D-28, D-30, D-35): any player creates one per hub, at most; an
 * anchor is shared by all the players of its server and dimension (listed and usable by them), never seen from another
 * server; names are unique per server, ignoring case; two anchors' boxes never overlap; the owner or an admin (D-26)
 * deletes it. A mirror hub (D-38) has admins' anchors only: one SOURCE (the server zone whose terrain is copied) and
 * one COPY per admin.
 */
@Service
public class HubAnchorService {

	private final HubAnchorRepository repository;
	private final HubRepository hubRepository;
	private final AdminService adminService;
	private final HubService hubService;

	public HubAnchorService(HubAnchorRepository repository, HubRepository hubRepository, AdminService adminService,
			HubService hubService) {
		this.repository = repository;
		this.hubRepository = hubRepository;
		this.adminService = adminService;
		this.hubService = hubService;
	}

	@Transactional
	public HubAnchorResponse create(UUID ownerUuid, HubAnchorCreateRequest request) {
		Hub hub = hubRepository.findByNameIgnoreCase(request.hub().strip()).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_HUB_NOT_FOUND", Map.of("hub", request.hub())));
		String role = role(ownerUuid, hub, request.role());
		if (HubAnchor.ROLE_SOURCE.equals(role)) {
			repository.findByHubUuidAndRole(hub.getUuid(), HubAnchor.ROLE_SOURCE).ifPresent(existing -> {
				throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_SOURCE_TAKEN",
						Map.of("anchor_uuid", existing.getUuid(), "name", existing.getName(), "hub", hub.getName()));
			});
		} else {
			repository.findByOwnerUuidAndHubUuidAndRoleNot(ownerUuid, hub.getUuid(), HubAnchor.ROLE_SOURCE).ifPresent(existing -> {
				throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_ANCHOR_QUOTA",
						Map.of("anchor_uuid", existing.getUuid(), "name", existing.getName(), "hub", hub.getName()));
			});
		}
		if (repository.existsByServerFingerprintAndNameIgnoreCase(request.serverFingerprint(), request.name())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_ANCHOR_NAME_TAKEN", Map.of("name", request.name()));
		}
		if (hub.isMirror()) {
			// D-38: centred on the block posed from, the whole height of the world, never turned.
			HubAnchor anchor = repository.saveAndFlush(new HubAnchor(UUID.randomUUID(), ownerUuid, hub.getUuid(), request.name(),
					request.serverFingerprint(), request.dimension(), Math.floor(request.origin().x()) + 0.5, Hub.MIRROR_MIN_Y,
					Math.floor(request.origin().z()) + 0.5, (short) 0, role));
			return HubAnchorResponse.from(anchor, hub);
		}
		short yaw = snapYaw(request.yaw());
		HubBox box = HubBox.of(request.origin().x(), request.origin().y(), request.origin().z(), yaw, hub.getSizeX(),
				hub.getSizeY(), hub.getSizeZ());
		Map<UUID, Hub> hubs = new HashMap<>();
		for (HubAnchor other : repository.findByServerFingerprintAndDimension(request.serverFingerprint(), request.dimension())) {
			Hub otherHub = hubs.computeIfAbsent(other.getHubUuid(), uuid -> hubRepository.findById(uuid).orElse(null));
			if (otherHub != null && box.intersects(HubBox.of(other, otherHub))) {
				throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_ANCHOR_OVERLAP",
						Map.of("anchor_uuid", other.getUuid(), "name", other.getName(), "hub", otherHub.getName()));
			}
		}
		HubAnchor anchor = repository.saveAndFlush(new HubAnchor(UUID.randomUUID(), ownerUuid, hub.getUuid(), request.name(),
				request.serverFingerprint(), request.dimension(),
				request.origin().x(), request.origin().y(), request.origin().z(), yaw, role));
		return HubAnchorResponse.from(anchor, hub);
	}

	/** The anchor's role: NORMAL in an ordinary hub; in a mirror hub, SOURCE or COPY and admins only (D-38). */
	private String role(UUID ownerUuid, Hub hub, String requested) {
		if (!hub.isMirror()) {
			if (requested != null && !HubAnchor.ROLE_NORMAL.equals(requested)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_HUB_ANCHOR_ROLE", Map.of("hub", hub.getName()));
			}
			return HubAnchor.ROLE_NORMAL;
		}
		if (!adminService.isAdmin(ownerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_HUB_MIRROR_ADMIN_ONLY", Map.of("hub", hub.getName()));
		}
		if (!HubAnchor.ROLE_SOURCE.equals(requested) && !HubAnchor.ROLE_COPY.equals(requested)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_HUB_ANCHOR_ROLE", Map.of("hub", hub.getName()));
		}
		return requested;
	}

	/** The anchors of one server and dimension: what its players see and may enter a hub through. */
	@Transactional(readOnly = true)
	public List<HubAnchorResponse> listForServer(String serverFingerprint, String dimension) {
		return withHubs(repository.findByServerFingerprintAndDimensionOrderByNameAsc(serverFingerprint, dimension));
	}

	/** The caller's anchors, one per hub at most. */
	@Transactional(readOnly = true)
	public List<HubAnchorResponse> findMine(UUID ownerUuid) {
		return withHubs(repository.findByOwnerUuidOrderByNameAsc(ownerUuid));
	}

	private List<HubAnchorResponse> withHubs(List<HubAnchor> anchors) {
		Map<UUID, Hub> hubs = new HashMap<>();
		return anchors.stream()
				.map(anchor -> HubAnchorResponse.from(anchor,
						hubs.computeIfAbsent(anchor.getHubUuid(), uuid -> hubRepository.findById(uuid).orElseThrow())))
				.toList();
	}

	@Transactional
	public void delete(UUID callerUuid, UUID anchorUuid) {
		HubAnchor anchor = repository.findById(anchorUuid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"ERROR_HUB_ANCHOR_NOT_FOUND", Map.of("anchor_uuid", anchorUuid)));
		if (!anchor.getOwnerUuid().equals(callerUuid) && !adminService.isAdmin(callerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_HUB_ANCHOR_FORBIDDEN", Map.of("anchor_uuid", anchorUuid));
		}
		repository.delete(anchor);
		hubService.onAnchorDeleted(anchorUuid);
	}

	/** Any Minecraft yaw (unbounded, possibly negative) → the nearest of 0, 90, 180, 270. */
	static short snapYaw(double yaw) {
		return (short) (Math.floorMod(Math.round(yaw / 90.0), 4) * 90);
	}
}
