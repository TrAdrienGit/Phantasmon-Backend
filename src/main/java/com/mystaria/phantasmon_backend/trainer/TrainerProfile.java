package com.mystaria.phantasmon_backend.trainer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A player's trainer animation profile (Cobblemon Spectacle, phase E3): moment id → animation name. */
@Entity
@Table(name = "trainer_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainerProfile {

	@Id
	@Column(name = "player_uuid")
	private UUID playerUuid;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private Map<String, String> profile;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public TrainerProfile(UUID playerUuid, Map<String, String> profile) {
		this.playerUuid = playerUuid;
		update(profile);
	}

	public void update(Map<String, String> profile) {
		this.profile = profile;
		this.updatedAt = Instant.now();
	}
}
