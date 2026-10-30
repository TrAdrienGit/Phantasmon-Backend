package com.mystaria.phantasmon_backend.events.tournament;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A match of a tournament: a Swiss round's pairing (or a bracket's, phase T4), played in best of 1, 3 or 5 Ghost
 * battles. {@code player2} null: a bye, won by player1. {@code PENDING} (waiting for its lobby), {@code PLAYING}
 * (lobby or battle open), {@code DONE}.
 */
@Entity
@Table(name = "tournament_matches")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TournamentMatch {

	public static final String PENDING = "PENDING";
	public static final String PLAYING = "PLAYING";
	public static final String DONE = "DONE";

	@Id
	private UUID uuid;

	@Column(name = "tournament_uuid", nullable = false)
	private UUID tournamentUuid;

	@Column(nullable = false)
	private short round;

	@Column(nullable = false, length = 8)
	private String bracket;

	@Column(nullable = false)
	private short position;

	@Column(name = "player1_uuid")
	private UUID player1;

	@Column(name = "player2_uuid")
	private UUID player2;

	@Column(nullable = false)
	private short wins1;

	@Column(nullable = false)
	private short wins2;

	@Column(name = "winner_uuid")
	private UUID winner;

	@Column(nullable = false, length = 8)
	private String status = PENDING;

	@Column(nullable = false)
	private boolean forfeit;

	@Column(name = "edited_by")
	private UUID editedBy;

	public TournamentMatch(UUID uuid, UUID tournamentUuid, int round, String bracket, int position, UUID player1, UUID player2) {
		this.uuid = uuid;
		this.tournamentUuid = tournamentUuid;
		this.round = (short) round;
		this.bracket = bracket;
		this.position = (short) position;
		this.player1 = player1;
		this.player2 = player2;
	}

	public boolean bye() {
		return player2 == null;
	}

	public boolean involves(UUID player) {
		return player.equals(player1) || player.equals(player2);
	}

	public UUID opponentOf(UUID player) {
		return player.equals(player1) ? player2 : player1;
	}
}
