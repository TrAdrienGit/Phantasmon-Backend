package com.mystaria.phantasmon_backend.trade;

import java.util.List;
import java.util.Map;
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

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.common.IdempotencyService;

@RestController
public class TradeController {

	private final TradeService tradeService;
	private final IdempotencyService idempotencyService;

	public TradeController(TradeService tradeService, IdempotencyService idempotencyService) {
		this.tradeService = tradeService;
		this.idempotencyService = idempotencyService;
	}

	@PostMapping("/trades")
	public ResponseEntity<TradeResponse> propose(@Valid @RequestBody ProposeTradeRequest request, Authentication authentication) {
		UUID initiatorUuid = playerUuid(authentication);
		TradeResponse response = idempotencyService.executeIdempotent(
				request.requestUuid(), initiatorUuid, "POST /trades",
				() -> tradeService.propose(initiatorUuid, request), TradeResponse.class);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/trades/{uuid}/accept")
	public TradeResponse accept(@PathVariable UUID uuid, Authentication authentication) {
		return tradeService.accept(playerUuid(authentication), uuid);
	}

	@PostMapping("/trades/{uuid}/cancel")
	public TradeResponse cancel(@PathVariable UUID uuid, Authentication authentication) {
		return tradeService.cancel(playerUuid(authentication), uuid);
	}

	@GetMapping("/trades/{uuid}")
	public TradeResponse get(@PathVariable UUID uuid, Authentication authentication) {
		return tradeService.get(playerUuid(authentication), uuid);
	}

	@GetMapping("/players/{uuid}/trades")
	public List<TradeResponse> listForPlayer(@PathVariable UUID uuid, Authentication authentication) {
		if (!uuid.equals(playerUuid(authentication))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", uuid));
		}
		return tradeService.listForPlayer(uuid);
	}

	private static UUID playerUuid(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
