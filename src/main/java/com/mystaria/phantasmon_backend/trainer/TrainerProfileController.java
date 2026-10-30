package com.mystaria.phantasmon_backend.trainer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * Trainers' animation profiles (Cobblemon Spectacle, phase E3): each client sends its own and reads those of the
 * trainers it sees (opponent, spectated battles, hub avatars), so everyone sees each trainer's style.
 *
 * <ul>
 * <li>{@code POST /trainer-profile} {@code {"guard": "guard_poised", ...}}: the caller's, replaced;</li>
 * <li>{@code GET /trainer-profiles?players=<uuid>,<uuid>}: theirs, players without one left out.</li>
 * </ul>
 * Only the known moments are kept, with a name of the form {@code <moment>_<style>}; the client falls back to the
 * default animation for one it doesn't have.
 */
@RestController
public class TrainerProfileController {

	static final Set<String> MOMENTS = Set.of("guard", "send_out", "recall", "attack", "victory", "defeat");
	private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
	private static final int MAX_PLAYERS = 64;

	private final TrainerProfileRepository repository;

	public TrainerProfileController(TrainerProfileRepository repository) {
		this.repository = repository;
	}

	@PostMapping("/trainer-profile")
	@Transactional
	public Map<String, String> save(@RequestBody Map<String, Object> body, Authentication authentication) {
		UUID caller = (UUID) authentication.getPrincipal();
		Map<String, String> profile = new LinkedHashMap<>();
		body.forEach((moment, value) -> {
			if (MOMENTS.contains(moment) && value instanceof String name && NAME.matcher(name).matches()
					&& name.startsWith(moment + "_")) {
				profile.put(moment, name);
			}
		});
		repository.findById(caller).ifPresentOrElse(existing -> existing.update(profile),
				() -> repository.save(new TrainerProfile(caller, profile)));
		return profile;
	}

	@GetMapping("/trainer-profiles")
	public Map<UUID, Map<String, String>> read(@RequestParam("players") List<UUID> players) {
		if (players.size() > MAX_PLAYERS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ERROR_TOO_MANY_PLAYERS", Map.of("max", MAX_PLAYERS));
		}
		Map<UUID, Map<String, String>> profiles = new HashMap<>();
		repository.findAllById(players).forEach(profile -> profiles.put(profile.getPlayerUuid(), profile.getProfile()));
		return profiles;
	}
}
