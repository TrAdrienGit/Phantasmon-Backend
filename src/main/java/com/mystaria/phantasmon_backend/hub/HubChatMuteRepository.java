package com.mystaria.phantasmon_backend.hub;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HubChatMuteRepository extends JpaRepository<HubChatMute, UUID> {
}
