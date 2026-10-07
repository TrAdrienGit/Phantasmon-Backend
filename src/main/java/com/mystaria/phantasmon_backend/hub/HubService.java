package com.mystaria.phantasmon_backend.hub;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.player.Player;
import com.mystaria.phantasmon_backend.player.PlayerService;
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;
import com.mystaria.phantasmon_backend.presence.PlayerPresence;
import com.mystaria.phantasmon_backend.presence.PresenceService;
import com.mystaria.phantasmon_backend.websocket.GhostPayloads;
import com.mystaria.phantasmon_backend.websocket.SessionRegistry;
import com.mystaria.phantasmon_backend.websocket.WsMessage;

import lombok.extern.slf4j.Slf4j;

/**
 * The Global Hub (network-cahier-des-charges.md §5.3 to §5.8): one public space, in memory like
 * {@link PresenceService}, holding at most {@code phantasmon.hub.capacity} players who entered it through a Hub
 * Anchor of the server and dimension they are on — any anchor of that server, theirs or another player's (D-30).
 * Positions are relative to the anchor (never real coordinates) and relayed to every other member as they come; a
 * client that sees a member for real where their avatar would stand hides that avatar itself.
 *
 * <p>A member's sent-out Ghost (N5) follows them into the Hub: {@code HubGhostSpawn} / {@code HubGhostDespawn} to
 * every other member, and in {@code HubJoined} for a late joiner — the same rendering data as
 * {@code GhostEntitySpawn}, without any position (the receiving client makes it follow the avatar).
 */
@Service
@Slf4j
public class HubService {

	static final int CHAT_MAX_LENGTH = 256;
	static final Duration CHAT_INTERVAL = Duration.ofSeconds(1);
	/** Cape, jacket, both sleeves, both pant legs, hat: Minecraft's {@code PlayerModelPart} masks. */
	static final int MAX_SKIN_PARTS = 0x7F;
	private static final Set<String> POSES = Set.of("STANDING", "CROUCHING", "SWIMMING", "FALL_FLYING");
	private static final List<String> NUMBERS = List.of("x", "z", "y_offset", "yaw", "head_yaw", "pitch");

	/** One player in the Hub; {@code state} is null until their first {@code HubMove}. */
	private record Member(UUID playerUuid, String username, UUID anchorUuid, String serverFingerprint, String dimension,
			Map<String, Object> state, Instant lastChatAt) {

		Map<String, Object> view() {
			Map<String, Object> view = new HashMap<>();
			view.put("player_uuid", playerUuid);
			view.put("username", username);
			view.put("state", state);
			return view;
		}
	}

	private final Map<UUID, Member> members = new ConcurrentHashMap<>();
	private final HubAnchorRepository anchorRepository;
	private final PresenceService presenceService;
	private final PlayerService playerService;
	private final PokemonRepository pokemonRepository;
	private final SessionRegistry sessionRegistry;
	private final Clock clock;
	private final int capacity;
	private final double halfSize;
	private final int anchorSize;

	public HubService(HubAnchorRepository anchorRepository, PresenceService presenceService, PlayerService playerService,
			PokemonRepository pokemonRepository, SessionRegistry sessionRegistry, Clock clock, @Value("${phantasmon.hub.capacity:50}") int capacity,
			@Value("${phantasmon.hub.anchor-size:21}") int anchorSize) {
		this.anchorRepository = anchorRepository;
		this.presenceService = presenceService;
		this.playerService = playerService;
		this.pokemonRepository = pokemonRepository;
		this.sessionRegistry = sessionRegistry;
		this.clock = clock;
		this.capacity = capacity;
		this.anchorSize = anchorSize;
		this.halfSize = anchorSize / 2.0;
	}

	/**
	 * {@code HubJoin}: the anchor must exist and stand on the server and dimension of the player's presence group —
	 * anchors are shared within a server, never across servers (D-30). Joining again is a leave followed by a join.
	 */
	public synchronized void join(UUID playerUuid, UUID anchorUuid) {
		HubAnchor anchor = anchorRepository.findById(anchorUuid).orElse(null);
		if (anchor == null) {
			error(playerUuid, "ERROR_HUB_ANCHOR_NOT_FOUND", Map.of("anchor_uuid", anchorUuid));
			return;
		}
		PlayerPresence presence = presenceService.find(playerUuid).orElse(null);
		if (presence == null || !anchor.getServerFingerprint().equals(presence.serverFingerprint())
				|| !anchor.getDimension().equals(presence.dimension())) {
			error(playerUuid, "ERROR_HUB_ANCHOR_WRONG_SERVER", Map.of("anchor_uuid", anchorUuid));
			return;
		}
		if (members.containsKey(playerUuid)) {
			leave(playerUuid, null);
		}
		if (members.size() >= capacity) {
			error(playerUuid, "ERROR_HUB_FULL", Map.of("capacity", capacity));
			return;
		}
		String username = playerService.findById(playerUuid).map(Player::getLastUsername).orElse("?");
		Member member = new Member(playerUuid, username, anchorUuid, anchor.getServerFingerprint(), anchor.getDimension(),
				null, null);
		List<Member> others = visibleTo(member);
		members.put(playerUuid, member);
		log.info("Player {} joined the Hub through anchor {} ({} / {} players)", username, anchor.getName(),
				members.size(), capacity);
		sessionRegistry.send(playerUuid, WsMessage.of("HubJoined",
				Map.of("members", others.stream().map(this::viewWithGhost).toList())));
		WsMessage enter = WsMessage.of("HubPlayerEnter", member.view());
		others.forEach(other -> sessionRegistry.send(other.playerUuid(), enter));
		ghostOf(playerUuid).ifPresent(ghost -> {
			WsMessage spawn = WsMessage.of("HubGhostSpawn", ghost);
			others.forEach(other -> sessionRegistry.send(other.playerUuid(), spawn));
		});
	}

	/**
	 * Takes the player out of the Hub, telling the members who saw them. {@code reason} ({@code LEFT},
	 * {@code SERVER_CHANGED}, {@code ANCHOR_DELETED}) is sent back as {@code HubLeft}; null for a disconnection.
	 */
	public synchronized void leave(UUID playerUuid, String reason) {
		Member member = members.remove(playerUuid);
		if (member == null) {
			return;
		}
		log.info("Player {} left the Hub ({})", member.username(), reason == null ? "disconnected" : reason);
		WsMessage leave = WsMessage.of("HubPlayerLeave", Map.of("player_uuid", playerUuid));
		visibleTo(member).forEach(other -> sessionRegistry.send(other.playerUuid(), leave));
		if (reason != null) {
			sessionRegistry.send(playerUuid, WsMessage.of("HubLeft", Map.of("reason", reason)));
		}
	}

	/** The player's presence moved to another server or dimension: the anchor they entered by no longer applies. */
	public void onServerGroup(UUID playerUuid, String serverFingerprint, String dimension) {
		Member member = members.get(playerUuid);
		if (member != null && !(member.serverFingerprint().equals(serverFingerprint) && member.dimension().equals(dimension))) {
			leave(playerUuid, "SERVER_CHANGED");
		}
	}

	/** Called once an anchor is deleted: whoever stands in the Hub through it is taken out. */
	public void onAnchorDeleted(UUID anchorUuid) {
		for (Member member : List.copyOf(members.values())) {
			if (member.anchorUuid().equals(anchorUuid)) {
				leave(member.playerUuid(), "ANCHOR_DELETED");
			}
		}
	}

	/**
	 * {@code HubMove}: Hub coordinates (relative to the anchor, rotated by its yaw on the client) must stay in the
	 * square, {@code y_offset} (height above the local ground) inside the cube; optional {@code skin_parts} (outer skin
	 * layers and cape shown, 0 to 127). Relayed to every other member.
	 */
	public void move(UUID playerUuid, Map<String, Object> data) {
		Member member = members.get(playerUuid);
		if (member == null) {
			error(playerUuid, "ERROR_HUB_NOT_JOINED", Map.of());
			return;
		}
		Map<String, Object> state = new LinkedHashMap<>();
		for (String key : NUMBERS) {
			if (!(data.get(key) instanceof Number number) || !Double.isFinite(number.doubleValue())) {
				error(playerUuid, "ERROR_WS_MALFORMED_MESSAGE", Map.of());
				return;
			}
			state.put(key, number.doubleValue());
		}
		if (!(data.get("pose") instanceof String pose) || !POSES.contains(pose)
				|| !(data.get("on_ground") instanceof Boolean onGround)) {
			error(playerUuid, "ERROR_WS_MALFORMED_MESSAGE", Map.of());
			return;
		}
		state.put("pose", pose);
		state.put("on_ground", onGround);
		// Optional: which outer skin layers and cape the player shows (Minecraft's 7-bit model-part mask).
		Object skinParts = data.get("skin_parts");
		if (skinParts != null) {
			if (!(skinParts instanceof Integer parts) || parts < 0 || parts > MAX_SKIN_PARTS) {
				error(playerUuid, "ERROR_WS_MALFORMED_MESSAGE", Map.of());
				return;
			}
			state.put("skin_parts", parts);
		}
		double x = (double) state.get("x");
		double z = (double) state.get("z");
		double yOffset = (double) state.get("y_offset");
		if (Math.abs(x) > halfSize || Math.abs(z) > halfSize || yOffset < 0 || yOffset > anchorSize) {
			error(playerUuid, "ERROR_HUB_OUT_OF_BOUNDS", Map.of("half_size", halfSize));
			return;
		}
		Member moved = members.computeIfPresent(playerUuid, (uuid, current) -> new Member(current.playerUuid(),
				current.username(), current.anchorUuid(), current.serverFingerprint(), current.dimension(), state,
				current.lastChatAt()));
		if (moved == null) {
			return;
		}
		WsMessage relay = WsMessage.of("HubPlayerMove", Map.of("player_uuid", playerUuid, "state", state));
		visibleTo(moved).forEach(other -> sessionRegistry.send(other.playerUuid(), relay));
	}

	/**
	 * {@code HubChat}: formatting codes ({@code §x}) removed, trimmed, at most {@value #CHAT_MAX_LENGTH} characters and
	 * one message per second. Logged, never stored; sent to every member, the sender and same-server members included.
	 */
	public void chat(UUID playerUuid, Object rawMessage) {
		Member member = members.get(playerUuid);
		if (member == null) {
			error(playerUuid, "ERROR_HUB_NOT_JOINED", Map.of());
			return;
		}
		String message = rawMessage instanceof String text ? text.replaceAll("§.?", "").strip() : "";
		if (message.isEmpty()) {
			error(playerUuid, "ERROR_WS_MALFORMED_MESSAGE", Map.of());
			return;
		}
		if (message.length() > CHAT_MAX_LENGTH) {
			error(playerUuid, "ERROR_HUB_CHAT_TOO_LONG", Map.of("max", CHAT_MAX_LENGTH));
			return;
		}
		Instant now = clock.instant();
		if (member.lastChatAt() != null && member.lastChatAt().plus(CHAT_INTERVAL).isAfter(now)) {
			error(playerUuid, "ERROR_HUB_CHAT_RATE_LIMITED", Map.of());
			return;
		}
		members.computeIfPresent(playerUuid, (uuid, current) -> new Member(current.playerUuid(), current.username(),
				current.anchorUuid(), current.serverFingerprint(), current.dimension(), current.state(), now));
		log.info("Hub chat <{}> {}", member.username(), message);
		WsMessage chat = WsMessage.of("HubChatMessage", Map.of("player_uuid", playerUuid, "username", member.username(),
				"message", message, "sent_at", now.toString()));
		members.keySet().forEach(recipient -> sessionRegistry.send(recipient, chat));
	}

	/** The member just sent a Ghost out: every other member sees it follow their avatar. */
	public void onGhostSentOut(UUID playerUuid, Pokemon pokemon) {
		Member member = members.get(playerUuid);
		if (member != null) {
			WsMessage spawn = WsMessage.of("HubGhostSpawn", GhostPayloads.withoutPosition(playerUuid, pokemon));
			visibleTo(member).forEach(other -> sessionRegistry.send(other.playerUuid(), spawn));
		}
	}

	/** The member's Ghost went back in (recall, trade, battle): gone for every other member. */
	public void onGhostRecalled(UUID playerUuid, UUID pokemonUuid) {
		Member member = members.get(playerUuid);
		if (member != null) {
			WsMessage despawn = WsMessage.of("HubGhostDespawn", Map.of("player_uuid", playerUuid, "pokemon_uuid", pokemonUuid));
			visibleTo(member).forEach(other -> sessionRegistry.send(other.playerUuid(), despawn));
		}
	}

	public boolean isMember(UUID playerUuid) {
		return members.containsKey(playerUuid);
	}

	/** Everyone in the Hub right now (one public Hub: they all see each other). */
	public Set<UUID> memberUuids() {
		return Set.copyOf(members.keySet());
	}

	/** A member as {@code HubJoined} lists them: avatar data, plus their sent-out Ghost or {@code null}. */
	private Map<String, Object> viewWithGhost(Member member) {
		Map<String, Object> view = member.view();
		view.put("ghost", ghostOf(member.playerUuid()).orElse(null));
		return view;
	}

	/** The rendering data of the Ghost {@code playerUuid} has out (from their presence), without any position. */
	private java.util.Optional<Map<String, Object>> ghostOf(UUID playerUuid) {
		return presenceService.find(playerUuid)
				.map(PlayerPresence::activeGhostPokemonUuid)
				.flatMap(pokemonRepository::findById)
				.map(pokemon -> GhostPayloads.withoutPosition(playerUuid, pokemon));
	}

	/** Every other member: they all get {@code member}'s avatar (a client seeing them for real hides it itself). */
	private List<Member> visibleTo(Member member) {
		List<Member> visible = new ArrayList<>();
		for (Member other : members.values()) {
			if (!Objects.equals(other.playerUuid(), member.playerUuid())) {
				visible.add(other);
			}
		}
		return visible;
	}

	private void error(UUID playerUuid, String errorCode, Map<String, Object> details) {
		sessionRegistry.send(playerUuid, WsMessage.error(errorCode, details));
	}
}
