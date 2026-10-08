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
 * deletes it.
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
		repository.findByOwnerUuidAndHubUuid(ownerUuid, hub.getUuid()).ifPresent(existing -> {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_ANCHOR_QUOTA",
					Map.of("anchor_uuid", existing.getUuid(), "name", existing.getName(), "hub", hub.getName()));
		});
		if (repository.existsByServerFingerprintAndNameIgnoreCase(request.serverFingerprint(), request.name())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_ANCHOR_NAME_TAKEN", Map.of("name", request.name()));
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
				request.origin().x(), request.origin().y(), request.origin().z(), yaw));
		return HubAnchorResponse.from(anchor, hub);
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
