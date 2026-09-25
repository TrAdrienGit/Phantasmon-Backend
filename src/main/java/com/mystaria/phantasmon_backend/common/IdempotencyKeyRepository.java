package com.mystaria.phantasmon_backend.common;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
}
