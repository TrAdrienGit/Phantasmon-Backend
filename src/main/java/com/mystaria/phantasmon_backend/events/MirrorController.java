package com.mystaria.phantasmon_backend.events;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The terrain of a mirror hub (D-38), for the clients that feed or build it. */
@RestController
public class MirrorController {

	private final MirrorService mirrorService;

	public MirrorController(MirrorService mirrorService) {
		this.mirrorService = mirrorService;
	}

	@GetMapping("/events/mirror/{hub}")
	public MirrorService.Info info(@PathVariable String hub) {
		return mirrorService.info(hub);
	}

	@GetMapping("/events/mirror/{hub}/tile/{tx}/{tz}")
	public MirrorService.TileView tile(@PathVariable String hub, @PathVariable int tx, @PathVariable int tz) {
		return mirrorService.tile(hub, tx, tz);
	}
}
