package com.mystaria.phantasmon_backend.reward;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.player.PlayerService;

import lombok.extern.slf4j.Slf4j;

/**
 * Rewards (TODO-37, Adrien 2026-11-11): looks are won (events, tournaments, the league) or given by an admin, never
 * picked freely. A player's collection says which filters and auras they may put on their Pokémon, and whether they
 * may resize them — on all their Pokémon or one species. A Pokémon's look lives in its data, {@code look}:
 * {@code {filter, aura, scale (50-200, 100 = none), sealed}}; {@link #checkLook} keeps a player within their
 * collection, and an admin's seal on a Pokémon out of their reach. Purely cosmetic: battles never read it.
 * A reward may also be a reserved battle intro or ambiance ({@code INTRO}, {@code AMBIANCE}, by Showtime id): the
 * client shows it among the player's choices; Showtime plays it.
 */
@Service
@Slf4j
public class RewardService {

	public static final String LOOK = "look";
	static final Set<String> KINDS = Set.of(PlayerReward.FILTER, PlayerReward.AURA, PlayerReward.SCALE, PlayerReward.INTRO,
			PlayerReward.AMBIANCE);
	/** A reserved intro's or ambiance's id: {@code <namespace>:<name>}. */
	private static final Pattern CONTENT_ID = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}");
	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
	private static final Pattern SPECIES = Pattern.compile("[a-z0-9_]{1,64}");
	public static final int SCALE_MIN = 50;
	public static final int SCALE_MAX = 200;

	private final PlayerRewardRepository repository;
	private final PlayerService playerService;
	private final com.mystaria.phantasmon_backend.websocket.SessionRegistry sessionRegistry;

	public RewardService(PlayerRewardRepository repository, PlayerService playerService,
			com.mystaria.phantasmon_backend.websocket.SessionRegistry sessionRegistry) {
		this.repository = repository;
		this.playerService = playerService;
		this.sessionRegistry = sessionRegistry;
	}

	/** {@code RewardsChanged} to the player, if connected: their client reads their rewards again at once. */
	private void notifyChanged(UUID playerUuid) {
		sessionRegistry.send(playerUuid, com.mystaria.phantasmon_backend.websocket.WsMessage.of("RewardsChanged", Map.of()));
	}

	public record RewardView(UUID uuid, String kind, String value, String species, String note, Instant grantedAt) {

		static RewardView of(PlayerReward reward) {
			return new RewardView(reward.getUuid(), reward.getKind(), reward.getValue(), reward.getSpecies(), reward.getNote(),
					reward.getGrantedAt());
		}
	}

	public record PlayerRewards(UUID playerUuid, String playerName, List<RewardView> rewards) {
	}

	@Transactional(readOnly = true)
	public List<RewardView> mine(UUID playerUuid) {
		return repository.findByPlayerUuidOrderByGrantedAtAsc(playerUuid).stream().map(RewardView::of).toList();
	}

	@Transactional(readOnly = true)
	public PlayerRewards ofPlayer(String name) {
		var player = playerService.findByName(name).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"ERROR_PLAYER_NOT_FOUND", Map.of("name", String.valueOf(name))));
		return new PlayerRewards(player.getUuid(), player.getLastUsername(), mine(player.getUuid()));
	}

	@Transactional
	public RewardView grant(UUID adminUuid, String playerName, String rawKind, String rawValue, String rawSpecies, String rawNote) {
		var player = playerService.findByName(playerName).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"ERROR_PLAYER_NOT_FOUND", Map.of("name", String.valueOf(playerName))));
		String kind = rawKind == null ? "" : rawKind.strip().toUpperCase(Locale.ROOT);
		if (!KINDS.contains(kind)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_REWARD_INVALID", Map.of("field", "kind"));
		}
		String value = PlayerReward.SCALE.equals(kind) ? "size" : rawValue == null ? "" : rawValue.strip().toLowerCase(Locale.ROOT);
		boolean content = PlayerReward.INTRO.equals(kind) || PlayerReward.AMBIANCE.equals(kind);
		if (!(content ? CONTENT_ID : ID).matcher(value).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_REWARD_INVALID", Map.of("field", "value"));
		}
		if (content && rawSpecies != null && !rawSpecies.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_REWARD_INVALID", Map.of("field", "species"));
		}
		String species = rawSpecies == null || rawSpecies.isBlank() ? null
				: rawSpecies.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		if (species != null && !SPECIES.matcher(species).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_REWARD_INVALID", Map.of("field", "species"));
		}
		String note = rawNote == null || rawNote.isBlank() ? null : rawNote.strip();
		if (note != null && note.length() > 100) {
			note = note.substring(0, 100);
		}
		PlayerReward reward = new PlayerReward(UUID.randomUUID(), player.getUuid(), kind, value, species, note, adminUuid, Instant.now());
		try {
			repository.saveAndFlush(reward);
		} catch (DataIntegrityViolationException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_REWARD_DUPLICATE", Map.of("kind", kind, "value", value));
		}
		log.info("Admin {} gave {} the reward {} {} ({})", adminUuid, player.getLastUsername(), kind, value,
				species == null ? "all species" : species);
		notifyChanged(player.getUuid());
		return RewardView.of(reward);
	}

	@Transactional
	public void revoke(UUID adminUuid, UUID rewardUuid) {
		PlayerReward reward = repository.findById(rewardUuid).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"ERROR_REWARD_NOT_FOUND", Map.of("uuid", rewardUuid)));
		repository.delete(reward);
		notifyChanged(reward.getPlayerUuid());
		log.info("Admin {} took the reward {} {} back from {}", adminUuid, reward.getKind(), reward.getValue(), reward.getPlayerUuid());
	}

	/**
	 * Checks a Pokémon's new {@code look} against the old one: an admin may set anything (and seal it); a player may
	 * only remove a part or put what their collection allows for this species, and never touch a sealed look.
	 */
	public void checkLook(UUID ownerUuid, boolean admin, String species, Map<String, Object> oldData, Map<String, Object> newData) {
		Object oldLook = oldData == null ? null : oldData.get(LOOK);
		Object newLook = newData == null ? null : newData.get(LOOK);
		Map<?, ?> next = newLook instanceof Map<?, ?> map ? map : null;
		if (newLook != null && next == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_LOOK_INVALID", Map.of());
		}
		if (next != null) {
			for (Object key : next.keySet()) {
				if (!Set.of("filter", "aura", "scale", "sealed").contains(String.valueOf(key))) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_LOOK_INVALID", Map.of("field", String.valueOf(key)));
				}
			}
			Integer scale = scaleOf(next);
			if (next.get("scale") != null && (scale == null || scale < SCALE_MIN || scale > SCALE_MAX)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_LOOK_INVALID", Map.of("field", "scale"));
			}
			for (String part : List.of("filter", "aura")) {
				Object id = next.get(part);
				if (id != null && !(id instanceof String text && ID.matcher(text).matches())) {
					throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_LOOK_INVALID", Map.of("field", part));
				}
			}
		}
		if (admin || Objects.equals(oldLook, newLook)) {
			return;
		}
		Map<?, ?> previous = oldLook instanceof Map<?, ?> map ? map : Map.of();
		if (Boolean.TRUE.equals(previous.get("sealed"))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_LOOK_SEALED", Map.of());
		}
		Map<?, ?> wanted = next == null ? Map.of() : next;
		if (Boolean.TRUE.equals(wanted.get("sealed"))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_LOOK_NOT_OWNED", Map.of("part", "sealed"));
		}
		List<PlayerReward> rewards = repository.findByPlayerUuidOrderByGrantedAtAsc(ownerUuid);
		for (String part : List.of("filter", "aura")) {
			Object id = wanted.get(part);
			if (id == null || id.equals(previous.get(part))) {
				continue; // removed or unchanged
			}
			String kind = "filter".equals(part) ? PlayerReward.FILTER : PlayerReward.AURA;
			boolean owned = rewards.stream().anyMatch(reward -> reward.getKind().equals(kind) && reward.getValue().equals(id)
					&& reward.covers(species));
			if (!owned) {
				throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_LOOK_NOT_OWNED", Map.of("part", part, "value", id));
			}
		}
		Integer scale = scaleOf(wanted);
		if (scale != null && scale != 100 && !Objects.equals(scale, scaleOf(previous))) {
			boolean owned = rewards.stream().anyMatch(reward -> reward.getKind().equals(PlayerReward.SCALE) && reward.covers(species));
			if (!owned) {
				throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_LOOK_NOT_OWNED", Map.of("part", "scale"));
			}
		}
	}

	private static Integer scaleOf(Map<?, ?> look) {
		return look.get("scale") instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
				? number.intValue() : null;
	}
}
