package com.mystaria.phantasmon_backend.battle;

import java.util.List;
import java.util.Map;
import java.util.UUID;


import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.mystaria.phantasmon_backend.common.ApiException;


@RestController
public class BattleController {

	private final BattleService battleService;
	private final LiveBattleService liveBattleService;

	public BattleController(BattleService battleService, LiveBattleService liveBattleService) {
		this.battleService = battleService;
		this.liveBattleService = liveBattleService;
	}

	/** The battle formats a player may pick, as in the lobby's picker (2026-11-28: the PC's team rows). */
	@GetMapping("/battles/formats")
	public List<Map<String, Object>> formats() {
		return liveBattleService.offeredFormats();
	}

	/** A PC team row's check against a format: {@code {format_id, pokemon: [uuid | null]}} → the issues per entry. */
	@PostMapping("/battles/validate")
	public Map<String, Object> validate(@RequestBody ValidateRequest request, Authentication authentication) {
		List<List<Map<String, String>>> issues = liveBattleService.validateOwned(playerUuid(authentication),
				request.formatId(), request.pokemon());
		if (issues == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_BATTLE_UNKNOWN_FORMAT", Map.of());
		}
		return Map.of("issues", issues);
	}

	public record ValidateRequest(String formatId, List<UUID> pokemon) {
	}

	@GetMapping("/battles/{uuid}")
	public BattleResponse get(@PathVariable UUID uuid, Authentication authentication) {
		return battleService.get(playerUuid(authentication), uuid);
	}


	private static UUID playerUuid(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
