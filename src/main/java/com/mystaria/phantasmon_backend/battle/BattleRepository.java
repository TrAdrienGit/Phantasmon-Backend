package com.mystaria.phantasmon_backend.battle;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BattleRepository extends JpaRepository<BattleSession, UUID> {
}
