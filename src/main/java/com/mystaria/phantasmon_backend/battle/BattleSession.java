package com.mystaria.phantasmon_backend.battle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A Ghost vs Ghost battle session (CAD Partie 2 §9, PHANTASMON_DB_SCHEMA.md §6).
 * {@code teamA}/{@code teamB} are JSONB snapshots of the pokemon uuids involved
 * at battle start — no SQL FK, since they must survive the pokemon being
 * edited/deleted later. V1 stores structure + result guardrails only; the
 * actual combat engine runs on the host client (client Phase 9).
 */
@Entity
@Table(name = "battle_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BattleSession {

	@Id
	private UUID uuid;

	@Column(name = "player_a", nullable = false)
	private UUID playerA;

	@Column(name = "player_b", nullable = false)
	private UUID playerB;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "team_a", nullable = false)
	private List<UUID> teamA;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "team_b", nullable = false)
	private List<UUID> teamB;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private BattleStatus status;

	@Setter
	@JdbcTypeCode(SqlTypes.JSON)
	@Column
	private Map<String, Object> result;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Setter
	@Column(name = "finished_at")
	private Instant finishedAt;

	public BattleSession(UUID uuid, UUID playerA, UUID playerB, List<UUID> teamA, List<UUID> teamB) {
		this.uuid = uuid;
		this.playerA = playerA;
		this.playerB = playerB;
		this.teamA = teamA;
		this.teamB = teamB;
		this.status = BattleStatus.ACTIVE;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
