package com.mystaria.phantasmon_backend.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.admin.AdminService;

/** The terrain of a mirror hub (D-38), for the clients that feed or build it, and its ghost blocks. */
@RestController
public class MirrorController {

	private final MirrorService mirrorService;
	private final AdminService adminService;

	public MirrorController(MirrorService mirrorService, AdminService adminService) {
		this.mirrorService = mirrorService;
		this.adminService = adminService;
	}

	@GetMapping("/events/mirror/{hub}")
	public MirrorService.Info info(@PathVariable String hub) {
		return mirrorService.info(hub);
	}

	@GetMapping("/events/mirror/{hub}/tile/{tx}/{tz}")
	public MirrorService.TileView tile(@PathVariable String hub, @PathVariable int tx, @PathVariable int tz) {
		return mirrorService.tile(hub, tx, tz);
	}

	/** The hub's ghost blocks, {@code [x, y, z, state]} zone-relative. */
	@GetMapping("/events/mirror/{hub}/ghosts")
	public List<List<Object>> ghosts(@PathVariable String hub) {
		return mirrorService.ghosts(hub);
	}

	/** Removes all the hub's ghost blocks; the real blocks come back everywhere. */
	@DeleteMapping("/admin/events/mirror/{hub}/ghosts")
	public Map<String, Object> clearGhosts(@PathVariable String hub, Authentication authentication) {
		UUID admin = (UUID) authentication.getPrincipal();
		adminService.requireAdmin(admin);
		return Map.of("removed", mirrorService.clearGhosts(hub));
	}
}
