package com.mystaria.phantasmon_backend.pokemon;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.common.IdempotencyService;

/**
 * Pokémon CRUD (CAD Partie 1 §7/§9/§18/§19/§20, Phase 1). Ownership is always
 * re-derived from the authenticated JWT ({@link Authentication#getPrincipal()},
 * a {@link UUID} set by {@code JwtAuthenticationFilter}) — never trusted from
 * a path variable or request body alone.
 */
@RestController
public class PokemonController {

	private final PokemonService pokemonService;
	private final IdempotencyService idempotencyService;

	public PokemonController(PokemonService pokemonService, IdempotencyService idempotencyService) {
		this.pokemonService = pokemonService;
		this.idempotencyService = idempotencyService;
	}

	@PostMapping("/pokemon")
	public ResponseEntity<PokemonResponse> create(@Valid @RequestBody PokemonCreateRequest request, Authentication authentication) {
		UUID ownerUuid = playerUuid(authentication);
		PokemonResponse response = idempotencyService.executeIdempotent(
				request.requestUuid(), ownerUuid, "POST /pokemon",
				() -> pokemonService.create(ownerUuid, request), PokemonResponse.class);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/players/{uuid}/pokemon")
	public List<PokemonResponse> list(@PathVariable UUID uuid, Authentication authentication) {
		requireSelf(uuid, authentication);
		return pokemonService.listForOwner(uuid);
	}

	@GetMapping("/players/{uuid}/pc")
	public List<PokemonResponse> pcBox(@PathVariable UUID uuid, @RequestParam int box, Authentication authentication) {
		requireSelf(uuid, authentication);
		return pokemonService.pcBox(uuid, box);
	}

	@PatchMapping("/pokemon/{uuid}")
	public PokemonResponse update(@PathVariable UUID uuid, @Valid @RequestBody PokemonUpdateRequest request, Authentication authentication) {
		return pokemonService.update(playerUuid(authentication), uuid, request);
	}

	@DeleteMapping("/pokemon/{uuid}")
	public ResponseEntity<Void> delete(@PathVariable UUID uuid, Authentication authentication) {
		pokemonService.delete(playerUuid(authentication), uuid);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/pokemon/{uuid}/clone")
	public ResponseEntity<PokemonResponse> clone(@PathVariable UUID uuid, Authentication authentication) {
		PokemonResponse response = pokemonService.clone(playerUuid(authentication), uuid);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	private static UUID playerUuid(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}

	private static void requireSelf(UUID pathUuid, Authentication authentication) {
		if (!pathUuid.equals(playerUuid(authentication))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", pathUuid));
		}
	}
}
