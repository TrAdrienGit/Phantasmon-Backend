package com.mystaria.phantasmon_backend.battle;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BattleRepository extends JpaRepository<BattleSession, UUID> {

	/** Live battles between two players, newest first — used to alternate the host. */
	@Query("select b from BattleSession b where b.hostUuid is not null and "
			+ "((b.playerA = :first and b.playerB = :second) or (b.playerA = :second and b.playerB = :first)) "
			+ "order by b.createdAt desc")
	List<BattleSession> findHostedBetween(@Param("first") UUID first, @Param("second") UUID second);

	List<BattleSession> findByStatus(BattleStatus status);
}
