package com.mystaria.phantasmon_backend.trainer;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainerProfileRepository extends JpaRepository<TrainerProfile, UUID> {
}
