package com.mystaria.phantasmon_backend.hub;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.admin.AdminService;
import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * Hub Anchors (network-cahier-des-charges.md §5.2, D-28): any player creates one, at most; names are unique per
 * server, ignoring case; the owner or an admin (D-26) deletes it. Without a server mod nothing proves the creator
 * really stands there — a limit D-29 (joining the Hub needs consent) makes harmless.
 */
@Service
public class HubAnchorService {

	private final HubAnchorRepository repository;
	private final AdminService adminService;
	private final HubService hubService;
	private final int anchorSize;

	public HubAnchorService(HubAnchorRepository repository, AdminService adminService, HubService hubService,
			@Value("${phantasmon.hub.anchor-size:21}") int anchorSize) {
		this.repository = repository;
		this.adminService = adminService;
		this.hubService = hubService;
		this.anchorSize = anchorSize;
	}

	@Transactional
	public HubAnchorResponse create(UUID ownerUuid, HubAnchorCreateRequest request) {
		repository.findByOwnerUuid(ownerUuid).ifPresent(existing -> {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_ANCHOR_QUOTA",
					Map.of("anchor_uuid", existing.getUuid(), "name", existing.getName()));
		});
		if (repository.existsByServerFingerprintAndNameIgnoreCase(request.serverFingerprint(), request.name())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_HUB_ANCHOR_NAME_TAKEN", Map.of("name", request.name()));
		}
		HubAnchor anchor = repository.saveAndFlush(new HubAnchor(UUID.randomUUID(), ownerUuid, request.name(),
				request.serverFingerprint(), request.dimension(),
				request.origin().x(), request.origin().y(), request.origin().z(), snapYaw(request.yaw())));
		return HubAnchorResponse.from(anchor, anchorSize);
	}

	@Transactional(readOnly = true)
	public List<HubAnchorResponse> listForServer(String serverFingerprint, String dimension) {
		return repository.findByServerFingerprintAndDimensionOrderByNameAsc(serverFingerprint, dimension).stream()
				.map(anchor -> HubAnchorResponse.from(anchor, anchorSize))
				.toList();
	}

	@Transactional(readOnly = true)
	public HubAnchorResponse findMine(UUID ownerUuid) {
		return repository.findByOwnerUuid(ownerUuid)
				.map(anchor -> HubAnchorResponse.from(anchor, anchorSize))
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_HUB_ANCHOR_NOT_FOUND", Map.of()));
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
