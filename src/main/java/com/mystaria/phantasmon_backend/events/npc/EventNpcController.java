package com.mystaria.phantasmon_backend.events.npc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.battle.BattleTeam;
import com.mystaria.phantasmon_backend.battle.CobblemonPartyParser;
import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * Events' trainer NPCs (see {@link EventNpcService}):
 * <ul>
 * <li>{@code GET /events/npcs?server=&dimension=}: those the caller may see here;</li>
 * <li>{@code GET /admin/events}: the events, open or not;</li>
 * <li>{@code POST /admin/events/npcs}: pose one ({@code event}, {@code name}, place);</li>
 * <li>{@code POST /admin/events/npcs/{uuid}}: edit it; {@code .../team}: its team from the admin's
 * ({@code {"team": "GHOST"}} or a Cobblemon party); {@code DELETE}: remove it;</li>
 * <li>{@code POST /admin/events/{name}/open} {@code {"open": bool}}.</li>
 * </ul>
 */
@RestController
public class EventNpcController {

	private final EventNpcService service;
	private final CobblemonPartyParser partyParser;

	public EventNpcController(EventNpcService service, CobblemonPartyParser partyParser) {
		this.service = service;
		this.partyParser = partyParser;
	}

	@GetMapping("/events/npcs")
	public List<Map<String, Object>> here(@RequestParam String server, @RequestParam String dimension, Authentication authentication) {
		return service.here(caller(authentication), server, dimension);
	}

	@GetMapping("/admin/events")
	public List<Map<String, Object>> events(Authentication authentication) {
		return service.eventList(caller(authentication));
	}

	@PostMapping("/admin/events/npcs")
	public Map<String, Object> create(@RequestBody Map<String, Object> body, Authentication authentication) {
		return service.create(caller(authentication), body);
	}

	@PostMapping("/admin/events/npcs/{uuid}")
	public Map<String, Object> update(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication authentication) {
		return service.update(caller(authentication), uuid, body);
	}

	@PostMapping("/admin/events/npcs/{uuid}/team")
	public Map<String, Object> team(@PathVariable UUID uuid, @RequestBody Map<String, Object> body, Authentication authentication) {
		UUID admin = caller(authentication);
		BattleTeam choice;
		try {
			choice = partyParser.parse(admin, body);
		} catch (CobblemonPartyParser.InvalidPartyException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_BATTLE_INVALID_PARTY", Map.of());
		}
		return service.setTeam(admin, uuid, choice);
	}

	@DeleteMapping("/admin/events/npcs/{uuid}")
	public Map<String, Object> delete(@PathVariable UUID uuid, Authentication authentication) {
		service.delete(caller(authentication), uuid);
		return Map.of("deleted", true);
	}

	@PostMapping("/admin/events/{name}/open")
	public Map<String, Object> open(@PathVariable String name, @RequestBody Map<String, Object> body, Authentication authentication) {
		return service.setOpen(caller(authentication), name, Boolean.TRUE.equals(body.get("open")));
	}

	private static UUID caller(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
