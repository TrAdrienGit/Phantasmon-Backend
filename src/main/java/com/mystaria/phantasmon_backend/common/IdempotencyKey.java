package com.mystaria.phantasmon_backend.common;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Anti-duplication record for sensitive POSTs (CAD Partie 2 §12,
 * PHANTASMON_DB_SCHEMA.md §7). Deliberately has no FK to {@code players} —
 * a technical dedup log, not business-relational data.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

	@Id
	@Column(name = "request_uuid")
	private UUID requestUuid;

	@Column(name = "player_uuid", nullable = false)
	private UUID playerUuid;

	@Column(nullable = false, length = 64)
	private String endpoint;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "response_snapshot")
	private Map<String, Object> responseSnapshot;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public IdempotencyKey(UUID requestUuid, UUID playerUuid, String endpoint, Map<String, Object> responseSnapshot) {
		this.requestUuid = requestUuid;
		this.playerUuid = playerUuid;
		this.endpoint = endpoint;
		this.responseSnapshot = responseSnapshot;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
