package com.mystaria.phantasmon_backend.battle;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * Read access to Ghost battle sessions (CAD Partie 2 §9, Partie 3 §A.1). Sessions are created, relayed and settled
 * only by the live battle flow ({@link LiveBattleService}, host chosen and result guardrails applied there); the
 * old REST create / result routes were removed (SEC-3, security audit 2026-10-04).
 */
@Service
public class BattleService {

	private final BattleRepository battleRepository;

	public BattleService(BattleRepository battleRepository) {
		this.battleRepository = battleRepository;
	}

	@Transactional(readOnly = true)
	public BattleResponse get(UUID callerUuid, UUID battleUuid) {
		BattleSession battle = findBattle(battleUuid);
		requireParticipant(callerUuid, battle);
		return BattleResponse.from(battle);
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
