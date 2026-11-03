package com.mystaria.phantasmon_backend.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * The mirror copies built by <b>our own</b> dedicated Minecraft server (Adrien 2026-11-02: the copy is no longer a
 * single-player world but that server's map, its small server mod writing the blocks). Not a player: it proves itself
 * with a shared token ({@code X-Phantasmon-Server-Token}, {@code phantasmon.mirror.server-token}); with no token
 * configured, every route answers 404.
 * <ul>
 * <li>{@code GET /server/mirror/copies?fingerprint=}: the copy anchors posed on that server;</li>
 * <li>{@code GET /server/mirror/{hub}}, {@code .../tile/{tx}/{tz}}, {@code .../ghosts}: as for the players;</li>
 * <li>{@code GET /server/mirror/{hub}/feed?since=}: what the copies were sent since then (see {@link MirrorService#feed});</li>
 * <li>{@code POST /server/mirror/{hub}/ghosts} {@code {changes}}: blocks placed or broken by hand in this server's
 * copy, shown as ghost blocks in the source zone and the other copies.</li>
 * </ul>
 */
@RestController
public class ServerMirrorController {

	private final MirrorService mirrorService;
	private final byte[] token;

	public ServerMirrorController(MirrorService mirrorService, @Value("${phantasmon.mirror.server-token:}") String token) {
		this.mirrorService = mirrorService;
		this.token = token.isBlank() ? null : token.strip().getBytes(StandardCharsets.UTF_8);
	}

	private void check(String given) {
		if (token == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ERROR_NOT_FOUND", Map.of());
		}
		if (given == null || !MessageDigest.isEqual(token, given.strip().getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_SERVER_TOKEN", Map.of());
		}
	}

	@GetMapping("/server/mirror/copies")
	public List<Map<String, Object>> copies(@RequestParam String fingerprint,
			@RequestHeader(value = "X-Phantasmon-Server-Token", required = false) String given) {
		check(given);
		return mirrorService.copiesOn(fingerprint);
	}

	@GetMapping("/server/mirror/{hub}")
	public MirrorService.Info info(@PathVariable String hub, @RequestHeader(value = "X-Phantasmon-Server-Token", required = false) String given) {
		check(given);
		return mirrorService.info(hub);
	}

	@GetMapping("/server/mirror/{hub}/tile/{tx}/{tz}")
	public MirrorService.TileView tile(@PathVariable String hub, @PathVariable int tx, @PathVariable int tz,
			@RequestHeader(value = "X-Phantasmon-Server-Token", required = false) String given) {
		check(given);
		return mirrorService.tile(hub, tx, tz);
	}

	@GetMapping("/server/mirror/{hub}/ghosts")
	public List<List<Object>> ghosts(@PathVariable String hub, @RequestHeader(value = "X-Phantasmon-Server-Token", required = false) String given) {
		check(given);
		return mirrorService.ghosts(hub);
	}

	/** Ghost blocks placed ({@code [x, y, z, state]}) or broken ({@code ""}) by hand in this server's copy. */
	@org.springframework.web.bind.annotation.PostMapping("/server/mirror/{hub}/ghosts")
	public Map<String, Object> ghosts(@PathVariable String hub, @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
			@RequestHeader(value = "X-Phantasmon-Server-Token", required = false) String given) {
		check(given);
		return Map.of("changed", mirrorService.receiveServerGhosts(hub, body.get("changes")));
	}

	@GetMapping("/server/mirror/{hub}/feed")
	public Map<String, Object> feed(@PathVariable String hub, @RequestParam(defaultValue = "0") long since,
			@RequestHeader(value = "X-Phantasmon-Server-Token", required = false) String given) {
		check(given);
		return mirrorService.feed(hub, since);
	}
}
