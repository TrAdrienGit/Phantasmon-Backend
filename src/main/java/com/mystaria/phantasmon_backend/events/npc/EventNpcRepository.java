package com.mystaria.phantasmon_backend.events.npc;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventNpcRepository extends JpaRepository<EventNpc, UUID> {

	List<EventNpc> findByServerFingerprintAndDimension(String serverFingerprint, String dimension);

	List<EventNpc> findByEventName(String eventName);

	List<EventNpc> findByHubUuidIn(java.util.Collection<UUID> hubUuids);
}
