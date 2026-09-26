package com.mystaria.phantasmon_backend.battle;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.common.IdempotencyService;

@RestController
public class BattleController {

	private final BattleService battleService;
	private final IdempotencyService idempotencyService;

	public BattleController(BattleService battleService, IdempotencyService idempotencyService) {
		this.battleService = battleService;
		this.idempotencyService = idempotencyService;
	}

	@PostMapping("/battles")
	public ResponseEntity<BattleResponse> create(@Valid @RequestBody CreateBattleRequest request, Authentication authentication) {
		UUID initiatorUuid = playerUuid(authentication);
		BattleResponse response = idempotencyService.executeIdempotent(
				request.requestUuid(), initiatorUuid, "POST /battles",
				() -> battleService.create(initiatorUuid, request), BattleResponse.class);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/battles/{uuid}")
	public BattleResponse get(@PathVariable UUID uuid, Authentication authentication) {
		return battleService.get(playerUuid(authentication), uuid);
	}

	@PostMapping("/battles/{uuid}/result")
	public BattleResponse submitResult(@PathVariable UUID uuid, @Valid @RequestBody SubmitBattleResultRequest request,
			Authentication authentication) {
		return battleService.submitResult(playerUuid(authentication), uuid, request);
	}

	private static UUID playerUuid(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
