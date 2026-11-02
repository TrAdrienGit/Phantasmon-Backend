package com.mystaria.phantasmon_backend.events.npc;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An event (Phantasmon Events): its NPCs show only while it is open. */
@Entity
@Table(name = "event_groups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventGroup {

	@Id
	private String name;

	@Setter
	@Column(nullable = false)
	private boolean open;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public EventGroup(String name, Instant createdAt) {
		this.name = name;
		this.createdAt = createdAt;
	}
}
