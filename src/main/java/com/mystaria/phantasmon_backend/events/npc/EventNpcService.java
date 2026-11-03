package com.mystaria.phantasmon_backend.events.npc;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.admin.AdminService;
import com.mystaria.phantasmon_backend.battle.BattleTeam;
import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.pokemon.PokemonResponse;
import com.mystaria.phantasmon_backend.pokemon.PokemonService;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * Events and their trainer NPCs (Phantasmon Events, Adrien 2026-11-02). An admin poses an NPC where they stand, in an
 * event (created on first use, closed); edits its name, skin, team (a copy of their Ghost team or Cobblemon party), AI
 * and format; opens and closes the event. Players see and challenge an event's NPCs only while it is open — admins
 * always, to set them up. Every change is broadcast ({@code EventsNpcsChanged}): clients list them again.
 */
@Service
@Slf4j
public class EventNpcService {

	/** AI levels: Cobblemon's random AI, its "strong" AI at rising skill, then Phantasmon's own {@code expert}. */
	public static final Set<String> AIS = Set.of("random", "easy", "normal", "hard", "veryhard", "expert");
	static final Pattern NAME = Pattern.compile("[A-Za-z0-9_ éèêàçÉÈ'-]{1,32}");
	static final Pattern EVENT = Pattern.compile("[a-z0-9_]{1,32}");
	static final Pattern SKIN = Pattern.compile("[A-Za-z0-9_]{1,16}");
	static final int MAX_TEAM = 6;

	private final EventNpcRepository npcs;
	private final EventGroupRepository events;
	private final AdminService adminService;
	private final PokemonService pokemonService;
	private final SessionRegistry sessionRegistry;
	private final Clock clock;
	private final com.mystaria.phantasmon_backend.hub.HubRepository hubs;
	private final com.mystaria.phantasmon_backend.hub.HubAnchorRepository anchors;

	public EventNpcService(EventNpcRepository npcs, EventGroupRepository events, AdminService adminService,
			PokemonService pokemonService, SessionRegistry sessionRegistry, Clock clock,
			com.mystaria.phantasmon_backend.hub.HubRepository hubs, com.mystaria.phantasmon_backend.hub.HubAnchorRepository anchors) {
		this.hubs = hubs;
		this.anchors = anchors;
		this.npcs = npcs;
		this.events = events;
		this.adminService = adminService;
		this.pokemonService = pokemonService;
		this.sessionRegistry = sessionRegistry;
		this.clock = clock;
	}

	/**
	 * The NPCs the caller may see here (those of open events, or all for an admin): posed in this server and
	 * dimension outside any hub, and those of every hub with an anchor here (Adrien 2026-11-03: a hub's NPC is shared by
	 * all its zones; the client places it in each).
	 */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> here(UUID caller, String serverFingerprint, String dimension) {
		boolean admin = adminService.isAdmin(caller);
		List<Map<String, Object>> result = new ArrayList<>();
		List<EventNpc> seen = new ArrayList<>();
		for (EventNpc npc : npcs.findByServerFingerprintAndDimension(serverFingerprint, dimension)) {
			if (npc.getHubUuid() == null) {
				seen.add(npc);
			}
		}
		java.util.Set<UUID> hubsHere = new java.util.HashSet<>();
		anchors.findByServerFingerprintAndDimension(serverFingerprint, dimension).forEach(anchor -> hubsHere.add(anchor.getHubUuid()));
		if (!hubsHere.isEmpty()) {
			seen.addAll(npcs.findByHubUuidIn(hubsHere));
		}
		for (EventNpc npc : seen) {
			boolean open = events.findById(npc.getEventName()).map(EventGroup::isOpen).orElse(false);
			if (open || admin) {
				result.add(view(npc, open, admin));
			}
		}
		return result;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> eventList(UUID admin) {
		adminService.requireAdmin(admin);
		List<Map<String, Object>> result = new ArrayList<>();
		for (EventGroup group : events.findAll()) {
			result.add(Map.of("name", group.getName(), "open", group.isOpen(), "npcs", npcs.findByEventName(group.getName()).size()));
		}
		return result;
	}

	@Transactional
	public Map<String, Object> create(UUID admin, Map<String, Object> body) {
		adminService.requireAdmin(admin);
		String event = text(body, "event", EVENT, "ERROR_EVENT_NAME");
		String name = text(body, "name", NAME, "ERROR_NPC_NAME");
		String server = required(body, "server_fingerprint");
		String dimension = required(body, "dimension");
		EventGroup group = events.findById(event).orElseGet(() -> events.save(new EventGroup(event, clock.instant())));
		EventNpc npc = new EventNpc(UUID.randomUUID(), group.getName(), name, server, dimension, number(body, "x"),
				number(body, "y"), number(body, "z"), (float) number(body, "yaw"), admin, clock.instant());
		npc.setHubUuid(hubOf(body));
		npcs.save(npc);
		log.info("Event NPC {} '{}' posed by {} in event {}", npc.getUuid(), name, admin, event);
		changed();
		return view(npc, group.isOpen(), true);
	}

	/** Name, skin, AI, format, event, place — each optional. */
	@Transactional
	public Map<String, Object> update(UUID admin, UUID uuid, Map<String, Object> body) {
		adminService.requireAdmin(admin);
		EventNpc npc = find(uuid);
		if (body.containsKey("name")) {
			npc.setName(text(body, "name", NAME, "ERROR_NPC_NAME"));
		}
		if (body.containsKey("skin")) {
			Object skin = body.get("skin");
			npc.setSkin(skin == null || skin.toString().isBlank() ? null : text(body, "skin", SKIN, "ERROR_NPC_SKIN"));
		}
		if (body.containsKey("ai")) {
			String ai = String.valueOf(body.get("ai"));
			if (!AIS.contains(ai)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_NPC_AI", Map.of("ais", AIS));
			}
			npc.setAi(ai);
		}
		if (body.containsKey("ai_debug")) {
			npc.setAiDebug(Boolean.TRUE.equals(body.get("ai_debug")));
		}
		if (body.containsKey("format_id")) {
			npc.setFormatId(required(body, "format_id"));
		}
		if (body.containsKey("event")) {
			String event = text(body, "event", EVENT, "ERROR_EVENT_NAME");
			events.findById(event).orElseGet(() -> events.save(new EventGroup(event, clock.instant())));
			npc.setEventName(event);
		}
		if (body.containsKey("x")) {
			npc.setServerFingerprint(required(body, "server_fingerprint"));
			npc.setDimension(required(body, "dimension"));
			npc.setX(number(body, "x"));
			npc.setY(number(body, "y"));
			npc.setZ(number(body, "z"));
			npc.setYaw((float) number(body, "yaw"));
			npc.setHubUuid(hubOf(body));
		}
		npc.setUpdatedAt(clock.instant());
		changed();
		return view(npc, events.findById(npc.getEventName()).map(EventGroup::isOpen).orElse(false), true);
	}

	/** The NPC's team becomes a copy of the admin's Ghost team or Cobblemon party ({@code choice}). */
	@Transactional
	public Map<String, Object> setTeam(UUID admin, UUID uuid, BattleTeam choice) {
		adminService.requireAdmin(admin);
		EventNpc npc = find(uuid);
		List<PokemonResponse> team = new ArrayList<>(choice.isGhost() ? pokemonService.activeTeam(admin) : choice.party());
		if (team.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_BATTLE_EMPTY_TEAM", Map.of());
		}
		npc.setTeam(new ArrayList<>(team.subList(0, Math.min(MAX_TEAM, team.size()))));
		npc.setUpdatedAt(clock.instant());
		log.info("Event NPC {}: team of {} set by {}", uuid, team.size(), admin);
		changed();
		return view(npc, events.findById(npc.getEventName()).map(EventGroup::isOpen).orElse(false), true);
	}

	@Transactional
	public void delete(UUID admin, UUID uuid) {
		adminService.requireAdmin(admin);
		npcs.delete(find(uuid));
		log.info("Event NPC {} deleted by {}", uuid, admin);
		changed();
	}

	@Transactional
	public Map<String, Object> setOpen(UUID admin, String event, boolean open) {
		adminService.requireAdmin(admin);
		EventGroup group = events.findById(event).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "ERROR_EVENT_NOT_FOUND", Map.of("event", event)));
		group.setOpen(open);
		log.info("Event {} {} by {}", event, open ? "opened" : "closed", admin);
		changed();
		return Map.of("name", group.getName(), "open", open);
	}

	/** An NPC to battle: it must exist with a team, its event open (an admin may test it closed). */
	@Transactional(readOnly = true)
	public EventNpc forBattle(UUID player, UUID uuid) {
		EventNpc npc = npcs.findById(uuid).orElse(null);
		if (npc == null) {
			return null;
		}
		boolean open = events.findById(npc.getEventName()).map(EventGroup::isOpen).orElse(false);
		if (!open && !adminService.isAdmin(player)) {
			return null;
		}
		npc.getTeam().size(); // loaded inside the transaction
		return npc;
	}

	/** {@code hub} (a hub's name) when the place is given in that hub's coordinates; absent or null: a world place. */
	private UUID hubOf(Map<String, Object> body) {
		Object name = body.get("hub");
		if (name == null || name.toString().isBlank()) {
			return null;
		}
		return hubs.findByNameIgnoreCase(name.toString().strip()).map(com.mystaria.phantasmon_backend.hub.Hub::getUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_HUB_NOT_FOUND", Map.of("hub", name.toString())));
	}

	private EventNpc find(UUID uuid) {
		return npcs.findById(uuid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_NPC_NOT_FOUND", Map.of()));
	}

	private void changed() {
		sessionRegistry.broadcast(WsMessage.of("EventsNpcsChanged", Map.of()));
	}

	private Map<String, Object> view(EventNpc npc, boolean open, boolean admin) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("uuid", npc.getUuid());
		// In a hub: x, y, z, yaw are hub coordinates.
		view.put("hub", npc.getHubUuid() == null ? null
				: hubs.findById(npc.getHubUuid()).map(com.mystaria.phantasmon_backend.hub.Hub::getName).orElse(null));
		view.put("event", npc.getEventName());
		view.put("open", open);
		view.put("name", npc.getName());
		view.put("skin", npc.getSkin());
		view.put("x", npc.getX());
		view.put("y", npc.getY());
		view.put("z", npc.getZ());
		view.put("yaw", npc.getYaw());
		view.put("ai", npc.getAi());
		view.put("ai_debug", npc.isAiDebug());
		view.put("format_id", npc.getFormatId());
		List<String> species = new ArrayList<>();
		for (PokemonResponse pokemon : npc.getTeam()) {
			species.add(pokemon.species());
		}
		// Players see how many Pokémon; admins also which.
		view.put("team_size", species.size());
		if (admin) {
			view.put("team_species", species);
		}
		return view;
	}

	private static String text(Map<String, Object> body, String key, Pattern pattern, String error) {
		Object value = body.get(key);
		String text = value == null ? "" : value.toString().strip();
		if (!pattern.matcher(text).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, error, Map.of());
		}
		return text;
	}

	private static String required(Map<String, Object> body, String key) {
		Object value = body.get(key);
		if (value == null || value.toString().isBlank() || value.toString().length() > 128) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_VALIDATION_FAILED", Map.of("field", key));
		}
		return value.toString();
	}

	private static double number(Map<String, Object> body, String key) {
		if (body.get(key) instanceof Number number && Double.isFinite(number.doubleValue())) {
			return number.doubleValue();
		}
		throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_VALIDATION_FAILED", Map.of("field", key));
	}
}
