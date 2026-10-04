package com.mystaria.phantasmon_backend.battle;

import java.util.UUID;


import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class BattleController {

	private final BattleService battleService;

	public BattleController(BattleService battleService) {
		this.battleService = battleService;
	}

	@GetMapping("/battles/{uuid}")
	public BattleResponse get(@PathVariable UUID uuid, Authentication authentication) {
		return battleService.get(playerUuid(authentication), uuid);
	}


	private static UUID playerUuid(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
