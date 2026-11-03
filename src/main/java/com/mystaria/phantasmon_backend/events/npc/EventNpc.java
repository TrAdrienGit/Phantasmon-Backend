package com.mystaria.phantasmon_backend.events.npc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.mystaria.phantasmon_backend.pokemon.PokemonResponse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A trainer NPC of an event (Adrien 2026-11-02): a place, a look, a team and the AI that plays it. */
@Entity
@Table(name = "event_npcs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventNpc {

	@Id
	private UUID uuid;

	@Column(name = "event_name", nullable = false)
	private String eventName;

	@Column(nullable = false)
	private String name;

	/** The Minecraft account whose skin it wears; null: its own name's. */
	private String skin;

	/** The hub it belongs to (V23): then {@code x, y, z, yaw} are hub coordinates; null: a place in the world. */
	@Column(name = "hub_uuid")
	private UUID hubUuid;

	@Column(name = "server_fingerprint", nullable = false)
	private String serverFingerprint;

	@Column(nullable = false)
	private String dimension;

	private double x;
	private double y;
	private double z;
	private float yaw;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<PokemonResponse> team = new ArrayList<>();

	/** One of {@link EventNpcService#AIS}. */
	@Column(nullable = false)
	private String ai;

	/** Its AI explains each decision in the player's chat (V24). */
	@Column(name = "ai_debug", nullable = false)
	private boolean aiDebug;

	@Column(name = "format_id", nullable = false)
	private String formatId;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public EventNpc(UUID uuid, String eventName, String name, String serverFingerprint, String dimension, double x, double y,
			double z, float yaw, UUID createdBy, Instant now) {
		this.uuid = uuid;
		this.eventName = eventName;
		this.name = name;
		this.serverFingerprint = serverFingerprint;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.ai = "normal";
		this.formatId = "free";
		this.createdBy = createdBy;
		this.updatedAt = now;
	}
}
