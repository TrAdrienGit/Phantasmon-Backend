package com.mystaria.phantasmon_backend.reward;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRewardRepository extends JpaRepository<PlayerReward, UUID> {

	List<PlayerReward> findByPlayerUuidOrderByGrantedAtAsc(UUID playerUuid);
}
