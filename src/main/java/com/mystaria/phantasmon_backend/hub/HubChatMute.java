package com.mystaria.phantasmon_backend.hub;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A player muted in the hubs' chat by an admin (TODO-31), until {@code mutedUntil}. */
@Entity
@Table(name = "hub_chat_mutes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HubChatMute {

	@Id
	@Column(name = "player_uuid")
	private UUID playerUuid;

	@Column(name = "muted_until", nullable = false)
	private Instant mutedUntil;

	@Column(name = "muted_by", nullable = false)
	private UUID mutedBy;

	@Column(name = "muted_at", nullable = false)
	private Instant mutedAt;

	public HubChatMute(UUID playerUuid, Instant mutedUntil, UUID mutedBy, Instant mutedAt) {
		this.playerUuid = playerUuid;
		this.mutedUntil = mutedUntil;
		this.mutedBy = mutedBy;
		this.mutedAt = mutedAt;
	}
}
