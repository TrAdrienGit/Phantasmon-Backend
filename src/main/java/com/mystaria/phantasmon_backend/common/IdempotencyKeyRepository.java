package com.mystaria.phantasmon_backend.common;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

	/**
	 * Reserves {@code requestUuid} atomically (DEBT-4): 1 if this call inserted it, 0 if it already exists. A
	 * concurrent transaction inserting the same key makes PostgreSQL wait for it to finish first, so a 0 always
	 * means the other request is committed and its row readable.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			INSERT INTO idempotency_keys (request_uuid, player_uuid, endpoint, response_snapshot, created_at)
			VALUES (:requestUuid, :playerUuid, :endpoint, NULL, now())
			ON CONFLICT (request_uuid) DO NOTHING
			""", nativeQuery = true)
	int claim(@Param("requestUuid") UUID requestUuid, @Param("playerUuid") UUID playerUuid, @Param("endpoint") String endpoint);
}
