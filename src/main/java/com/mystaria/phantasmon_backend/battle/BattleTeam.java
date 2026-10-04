package com.mystaria.phantasmon_backend.battle;

import java.util.List;

import com.mystaria.phantasmon_backend.pokemon.PokemonResponse;

/**
 * Which team a player brings to a live battle (CAD Partie 1 §31, Ghost vs normal Pokémon): their Ghost active team
 * ({@link #GHOST}, read from the database when the battle starts) or a <b>copy</b> of their real Cobblemon party,
 * sent by their client and validated by {@link CobblemonPartyParser}. The real Pokémon are never touched: the battle
 * is played on throwaway copies, like every Ghost battle.
 */
public record BattleTeam(Source source, List<PokemonResponse> party) {

	public enum Source { GHOST, COBBLEMON }

	public static final BattleTeam GHOST = new BattleTeam(Source.GHOST, List.of());

	public boolean isGhost() {
		return source == Source.GHOST;
	}
}
