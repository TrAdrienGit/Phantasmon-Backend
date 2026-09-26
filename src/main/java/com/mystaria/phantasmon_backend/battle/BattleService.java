package com.mystaria.phantasmon_backend.battle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.player.PlayerRepository;
import com.mystaria.phantasmon_backend.pokemon.Pokemon;
import com.mystaria.phantasmon_backend.pokemon.PokemonRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Ghost battle sessions (CAD Partie 2 §9, Partie 3 §A.1, backend Phase 4).
 * V1 stores structure and applies best-effort result guardrails only — the
 * actual combat engine runs client-side on the host (client Phase 9); this
 * service never simulates or scores a battle.
 */
@Service
@Slf4j
public class BattleService {

	private static final int MAX_TEAM_SIZE = 6;

	private final BattleRepository battleRepository;
	private final PlayerRepository playerRepository;
	private final PokemonRepository pokemonRepository;

	public BattleService(BattleRepository battleRepository, PlayerRepository playerRepository,
			PokemonRepository pokemonRepository) {
		this.battleRepository = battleRepository;
		this.playerRepository = playerRepository;
		this.pokemonRepository = pokemonRepository;
	}

	@Transactional
	public BattleResponse create(UUID initiatorUuid, CreateBattleRequest request) {
		if (initiatorUuid.equals(request.opponentUuid())) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_BATTLE_SELF", Map.of());
		}
		if (!playerRepository.existsById(request.opponentUuid())) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ERROR_PLAYER_NOT_FOUND",
					Map.of("uuid", request.opponentUuid()));
		}

		List<UUID> team = validateOwnTeam(initiatorUuid, request.team());

		List<UUID> opponentTeam = pokemonRepository
				.findByOwnerUuidAndTeamSlotIsNotNullOrderByTeamSlot(request.opponentUuid()).stream()
				.map(Pokemon::getUuid).toList();
		if (opponentTeam.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_BATTLE_OPPONENT_NO_TEAM",
					Map.of("uuid", request.opponentUuid()));
		}

		BattleSession battle = new BattleSession(UUID.randomUUID(), initiatorUuid, request.opponentUuid(),
				team, opponentTeam);
		battleRepository.save(battle);

		log.info("Battle {} started: {} vs {}", battle.getUuid(), initiatorUuid, request.opponentUuid());

		return BattleResponse.from(battle);
	}

	@Transactional(readOnly = true)
	public BattleResponse get(UUID callerUuid, UUID battleUuid) {
		BattleSession battle = findBattle(battleUuid);
		requireParticipant(callerUuid, battle);
		return BattleResponse.from(battle);
	}

	@Transactional
	public BattleResponse submitResult(UUID callerUuid, UUID battleUuid, SubmitBattleResultRequest request) {
		BattleSession battle = findBattle(battleUuid);
		requireParticipant(callerUuid, battle);

		if (battle.getStatus() != BattleStatus.ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_BATTLE_INVALID_STATE",
					Map.of("uuid", battleUuid, "status", battle.getStatus().name()));
		}
		if (!request.winnerUuid().equals(battle.getPlayerA()) && !request.winnerUuid().equals(battle.getPlayerB())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_BATTLE_INVALID_RESULT",
					Map.of("winner_uuid", request.winnerUuid()));
		}

		battle.setStatus(BattleStatus.FINISHED);
		battle.setResult(Map.of("winner_uuid", request.winnerUuid(), "log", request.log() == null ? Map.of() : request.log()));
		battle.setFinishedAt(Instant.now());
		battleRepository.save(battle);

		log.info("Battle {} finished, winner {}", battleUuid, request.winnerUuid());

		return BattleResponse.from(battle);
	}

	private List<UUID> validateOwnTeam(UUID initiatorUuid, List<UUID> requestedTeam) {
		if (requestedTeam.size() > MAX_TEAM_SIZE || Set.copyOf(requestedTeam).size() != requestedTeam.size()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_BATTLE_INVALID_TEAM",
					Map.of("team", requestedTeam));
		}
		for (UUID pokemonUuid : requestedTeam) {
			Pokemon pokemon = pokemonRepository.findById(pokemonUuid)
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND",
							Map.of("uuid", pokemonUuid)));
			if (!pokemon.getOwnerUuid().equals(initiatorUuid)) {
				throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH",
						Map.of("uuid", pokemonUuid));
			}
		}
		return requestedTeam;
	}

	private BattleSession findBattle(UUID battleUuid) {
		return battleRepository.findById(battleUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_BATTLE_NOT_FOUND", Map.of("uuid", battleUuid)));
	}

	private static void requireParticipant(UUID callerUuid, BattleSession battle) {
		if (!battle.getPlayerA().equals(callerUuid) && !battle.getPlayerB().equals(callerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", battle.getUuid()));
		}
	}
}
