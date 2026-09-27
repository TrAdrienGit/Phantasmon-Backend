package com.mystaria.phantasmon_backend.pokemon;

import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Matches the OpenAPI {@code PokemonUpdateRequest} schema — partial update.
 * PC and active-team storage are mutually exclusive (a Pokémon is never in
 * both at once, CAD Partie 1 §12/§17) — polish round, 2026-09-27:
 *
 * <p>{@code teamSlot} (destination team slot) and {@code boxId}/{@code boxSlot}
 * (destination PC slot) are alternative ways to say "move this Pokémon here"
 * — whichever one is given, the Pokémon ends up with only that kind of
 * location, the other cleared. A plain {@code null} on all three means "don't
 * move it" (partial-update convention). Uniform drag&drop semantics
 * (Adrien 2026-09-27) apply regardless of which combination of PC/team is
 * involved: an empty destination is a plain move, an occupied one **swaps**
 * the two Pokémon's locations — there is no automatic first-free-slot
 * fallback here, the player always names the exact destination.
 */
public record PokemonUpdateRequest(
		Map<String, Object> data,
		@Min(1) @Max(100) Integer level,
		@Min(1) @Max(6) Integer teamSlot,
		@Min(1) @Max(16) Integer boxId,
		@Min(1) @Max(30) Integer boxSlot) {
}
