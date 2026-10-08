package com.mystaria.phantasmon_backend.hub;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HubRepository extends JpaRepository<Hub, UUID> {

	Optional<Hub> findByNameIgnoreCase(String name);

	List<Hub> findAllByOrderByNameAsc();
}
