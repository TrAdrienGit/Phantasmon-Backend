package com.mystaria.phantasmon_backend.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.battle.LiveBattleService;
import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.hub.HubService;
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
	private final HubService hubService;
	private final com.mystaria.phantasmon_backend.websocket.SessionRegistry sessions;

	public AdminController(AdminService adminService, PlayerService playerService, LiveBattleService liveBattleService,
			BackendRestarter restarter, HubService hubService, com.mystaria.phantasmon_backend.websocket.SessionRegistry sessions) {
		this.hubService = hubService;
		this.sessions = sessions;
		this.adminService = adminService;
		this.playerService = playerService;
		this.liveBattleService = liveBattleService;
		this.restarter = restarter;
	}

	public record StopBattleRequest(String player) {
	}

	public record HubMuteRequest(String player, Long minutes) {
	}

	/** Whether the caller is an admin — the client shows its admin commands accordingly. */
	@GetMapping("/admin/me")
	public Map<String, Object> me(Authentication authentication) {
		return Map.of("admin", adminService.isAdmin(caller(authentication)));
	}

	/** The admins (the admin file's names), for the Admins page of the menu. */
	@GetMapping("/admin/admins")
	public Map<String, Object> admins(Authentication authentication) {
		adminService.requireAdmin(caller(authentication));
		return Map.of("admins", adminService.list());
	}

	@PostMapping("/admin/admins")
	public Map<String, Object> addAdmin(@RequestBody StopBattleRequest request, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		String name = request == null ? null : request.player();
		Map<String, Object> out = Map.of("admins", adminService.add(admin, name));
		adminChanged(name);
		return out;
	}

	@DeleteMapping("/admin/admins/{name}")
	public Map<String, Object> removeAdmin(@PathVariable String name, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		Map<String, Object> out = Map.of("admins", adminService.remove(admin, name));
		adminChanged(name);
		return out;
	}

	/**
	 * {@code AdminChanged} to the player added or removed, if connected (2026-11-25): their client asks
	 * {@code /admin/me} again, so the admin menu and commands come or go at once, no reconnection.
	 */
	private void adminChanged(String name) {
		playerService.findByName(name).ifPresent(player -> sessions.send(player.getUuid(),
				com.mystaria.phantasmon_backend.websocket.WsMessage.of("AdminChanged", Map.of())));
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

	/** Hub chat moderation (TODO-31): mutes {@code player} in the hubs' chat for {@code minutes}. */
	@PostMapping("/admin/hub/mute")
	public Map<String, Object> hubMute(@RequestBody HubMuteRequest request, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		Player player = byName(request == null ? null : request.player());
		long minutes = request.minutes() == null ? 0 : request.minutes();
		if (minutes < 1 || minutes > HubService.MUTE_MAX.toMinutes()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_HUB_MUTE_DURATION", Map.of("max_minutes", HubService.MUTE_MAX.toMinutes()));
		}
		Instant until = hubService.mute(admin, player.getUuid(), Duration.ofMinutes(minutes));
		return Map.of("player", player.getLastUsername(), "until", until.toString());
	}

	/** Lifts {@code player}'s hub chat mute. */
	@PostMapping("/admin/hub/unmute")
	public Map<String, Object> hubUnmute(@RequestBody StopBattleRequest request, Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		Player player = byName(request == null ? null : request.player());
		return Map.of("player", player.getLastUsername(), "was_muted", hubService.unmute(admin, player.getUuid()));
	}

	/** Global hub chat lock, toggled: while on, only admins write in the hubs' chat. */
	@PostMapping("/admin/hub/lock")
	public Map<String, Object> hubLock(Authentication authentication) {
		UUID admin = caller(authentication);
		adminService.requireAdmin(admin);
		return Map.of("locked", hubService.toggleChatLock(admin));
	}

	private Player byName(String name) {
		return playerService.findByName(name).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_PLAYER_NOT_FOUND", Map.of("name", name == null ? "" : name)));
	}

	private static UUID caller(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
