package com.mystaria.phantasmon_backend.admin;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.battle.LiveBattleService;
import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerService;

import lombok.extern.slf4j.Slf4j;

/**
 * Admin requests (TODO-25). Every one but {@code /admin/me} needs the caller to be in the admin file
 * ({@link AdminService}, {@code ERROR_ADMIN_REQUIRED} otherwise). Opening another player's PC goes through the usual
 * Pokémon endpoints, which let admins act for the owner; these add the name lookup, stopping a battle and the reboot.
 */
@RestController
@Slf4j
public class AdminController {

	private final AdminService adminService;
	private final PlayerService playerService;
	private final LiveBattleService liveBattleService;
	private final BackendRestarter restarter;

	public AdminController(AdminService adminService, PlayerService playerService, LiveBattleService liveBattleService,
			BackendRestarter restarter) {
		this.adminService = adminService;
		this.playerService = playerService;
		this.liveBattleService = liveBattleService;
		this.restarter = restarter;
	}

	public record StopBattleRequest(String player) {
	}

	/** Whether the caller is an admin — the client shows its admin commands accordingly. */
	@GetMapping("/admin/me")
	public Map<String, Object> me(Authentication authentication) {
		return Map.of("admin", adminService.isAdmin(caller(authentication)));
	}

	@GetMapping("/admin/players/{name}")
	public Map<String, Object> player(@PathVariable String name, Authentication authentication) {
		adminService.requireAdmin(caller(authentication));
		Player player = byName(name);
		return Map.of("uuid", player.getUuid(), "name", player.getLastUsername());
	}

	/** Ends the battle (draw, no winner) — or cancels the lobby — {@code player} is in. */
	@PostMapping("/admin/battles/stop")
	public Map<String, Object> stopBattle(@RequestBody StopBattleRequest request, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		Player player = byName(request == null ? null : request.player());
		String stopped = liveBattleService.adminStop(player.getUuid());
		if (stopped == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ERROR_ADMIN_NOTHING_TO_STOP", Map.of("player", player.getLastUsername()));
		}
		log.info("Admin {} stopped the {} of {}", admin, stopped.toLowerCase(), player.getLastUsername());
		return Map.of("stopped", stopped, "player", player.getLastUsername());
	}

	/** Restarts the backend (in the same process): ongoing battles end as draws, clients reconnect on their own. */
	@PostMapping("/admin/reboot")
	public ResponseEntity<Map<String, Object>> reboot(Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		log.warn("Backend reboot requested by admin {}", admin);
		restarter.restartSoon();
		return ResponseEntity.accepted().body(Map.of("rebooting", true));
	}

	private Player byName(String name) {
		return playerService.findByName(name).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_PLAYER_NOT_FOUND", Map.of("name", name == null ? "" : name)));
	}

	private static UUID caller(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
