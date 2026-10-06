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
@lombok.extern.slf4j.Slf4j
public class PokemonController {

	private final PokemonService pokemonService;
	private final IdempotencyService idempotencyService;
	private final com.mystaria.phantasmon_backend.admin.AdminService adminService;

	public PokemonController(PokemonService pokemonService, IdempotencyService idempotencyService,
			com.mystaria.phantasmon_backend.admin.AdminService adminService) {
		this.pokemonService = pokemonService;
		this.idempotencyService = idempotencyService;
		this.adminService = adminService;
	}

	@PostMapping("/pokemon")
	public ResponseEntity<PokemonResponse> create(@Valid @RequestBody PokemonCreateRequest request,
			@RequestParam(name = "owner", required = false) UUID owner, Authentication authentication) {
		UUID ownerUuid = playerUuid(authentication);
		if (owner != null && !owner.equals(ownerUuid)) {
			// Admin (TODO-25): creates in another player's PC (import from the admin PC screen).
			adminService.requireAdmin(ownerUuid);
			log.info("Admin {} creates a Pokémon for {}", ownerUuid, owner);
			ownerUuid = owner;
		}
		UUID finalOwner = ownerUuid;
		PokemonResponse response = idempotencyService.executeIdempotent(
				request.requestUuid(), finalOwner, "POST /pokemon",
				() -> pokemonService.create(finalOwner, request), PokemonResponse.class);
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
		return pokemonService.update(actingOwner(uuid, authentication), uuid, request);
	}

	@DeleteMapping("/pokemon/{uuid}")
	public ResponseEntity<Void> delete(@PathVariable UUID uuid, Authentication authentication) {
		pokemonService.delete(actingOwner(uuid, authentication), uuid);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/pokemon/{uuid}/clone")
	public ResponseEntity<PokemonResponse> clone(@PathVariable UUID uuid, Authentication authentication) {
		PokemonResponse response = pokemonService.clone(actingOwner(uuid, authentication), uuid);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	private static UUID playerUuid(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}

	/** Own data, or an admin's (TODO-25): admins see and handle any player's PC as if it were theirs. */
	private void requireSelf(UUID pathUuid, Authentication authentication) {
		UUID caller = playerUuid(authentication);
		if (!pathUuid.equals(caller) && !adminService.isAdmin(caller)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", pathUuid));
		}
	}

	/**
	 * The owner a change is made for: the caller — or, for an admin touching someone else's Pokémon, that Pokémon's
	 * owner, so every ownership rule of the service applies as if the owner did it.
	 */
	private UUID actingOwner(UUID pokemonUuid, Authentication authentication) {
		UUID caller = playerUuid(authentication);
		UUID owner = pokemonService.ownerOf(pokemonUuid);
		if (owner != null && !owner.equals(caller) && adminService.isAdmin(caller)) {
			log.info("Admin {} acts on Pokémon {} of {}", caller, pokemonUuid, owner);
			return owner;
		}
		return caller;
	}
}
