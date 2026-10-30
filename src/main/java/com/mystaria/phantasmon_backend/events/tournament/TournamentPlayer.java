package com.mystaria.phantasmon_backend.events.tournament;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A player registered in a tournament: checked in or not, dropped (left or kicked) or not. */
@Entity
@Table(name = "tournament_players")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TournamentPlayer {

	@Embeddable
	@Getter
	@EqualsAndHashCode
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class Key implements Serializable {

		@Column(name = "tournament_uuid")
		private UUID tournamentUuid;

		@Column(name = "player_uuid")
		private UUID playerUuid;

		public Key(UUID tournamentUuid, UUID playerUuid) {
			this.tournamentUuid = tournamentUuid;
			this.playerUuid = playerUuid;
		}
	}

	@EmbeddedId
	private Key key;

	@Column(nullable = false, length = 16)
	private String username;

	@Column(name = "checked_in", nullable = false)
	private boolean checkedIn;

	@Column(nullable = false)
	private boolean dropped;

	@Column(name = "registered_at", nullable = false, updatable = false)
	private Instant registeredAt;

	public TournamentPlayer(UUID tournamentUuid, UUID playerUuid, String username) {
		this.key = new Key(tournamentUuid, playerUuid);
		this.username = username;
	}

	public UUID playerUuid() {
		return key.getPlayerUuid();
	}

	@PrePersist
	void onCreate() {
		registeredAt = Instant.now();
	}
}
