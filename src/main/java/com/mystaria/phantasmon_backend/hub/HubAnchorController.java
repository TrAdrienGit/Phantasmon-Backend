package com.mystaria.phantasmon_backend.hub;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.common.IdempotencyService;

@RestController
public class HubAnchorController {

	private final HubAnchorService hubAnchorService;
	private final IdempotencyService idempotencyService;

	public HubAnchorController(HubAnchorService hubAnchorService, IdempotencyService idempotencyService) {
		this.hubAnchorService = hubAnchorService;
		this.idempotencyService = idempotencyService;
	}

	@PostMapping("/hub/anchors")
	public ResponseEntity<HubAnchorResponse> create(@Valid @RequestBody HubAnchorCreateRequest request, Authentication authentication) {
		UUID ownerUuid = playerUuid(authentication);
		HubAnchorResponse response = idempotencyService.executeIdempotent(
				request.requestUuid(), ownerUuid, "POST /hub/anchors",
				() -> hubAnchorService.create(ownerUuid, request), HubAnchorResponse.class);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/hub/anchors")
	public List<HubAnchorResponse> listForServer(@RequestParam("server_fingerprint") String serverFingerprint,
			@RequestParam String dimension) {
		return hubAnchorService.listForServer(serverFingerprint, dimension);
	}

	@GetMapping("/hub/anchors/mine")
	public List<HubAnchorResponse> mine(Authentication authentication) {
		return hubAnchorService.findMine(playerUuid(authentication));
	}

	@DeleteMapping("/hub/anchors/{uuid}")
	public ResponseEntity<Void> delete(@PathVariable UUID uuid, Authentication authentication) {
		hubAnchorService.delete(playerUuid(authentication), uuid);
		return ResponseEntity.noContent().build();
	}

	private static UUID playerUuid(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
