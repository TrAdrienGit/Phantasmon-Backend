package com.mystaria.phantasmon_backend.hub;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HubAnchorRepository extends JpaRepository<HubAnchor, UUID> {

	Optional<HubAnchor> findByOwnerUuidAndHubUuid(UUID ownerUuid, UUID hubUuid);

	List<HubAnchor> findByOwnerUuidOrderByNameAsc(UUID ownerUuid);

	List<HubAnchor> findByHubUuid(UUID hubUuid);

	List<HubAnchor> findByServerFingerprintAndDimension(String serverFingerprint, String dimension);

	boolean existsByServerFingerprintAndNameIgnoreCase(String serverFingerprint, String name);

	List<HubAnchor> findByServerFingerprintAndDimensionOrderByNameAsc(String serverFingerprint, String dimension);
}
