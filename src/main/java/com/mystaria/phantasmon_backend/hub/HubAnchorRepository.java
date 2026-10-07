package com.mystaria.phantasmon_backend.hub;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HubAnchorRepository extends JpaRepository<HubAnchor, UUID> {

	Optional<HubAnchor> findByOwnerUuid(UUID ownerUuid);

	boolean existsByServerFingerprintAndNameIgnoreCase(String serverFingerprint, String name);

	List<HubAnchor> findByServerFingerprintAndDimensionOrderByNameAsc(String serverFingerprint, String dimension);
}
