package com.mystaria.phantasmon_backend.pokemon;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonRepository extends JpaRepository<Pokemon, UUID> {

	List<Pokemon> findByOwnerUuid(UUID ownerUuid);

	List<Pokemon> findByOwnerUuidAndBoxId(UUID ownerUuid, Short boxId);

	List<Pokemon> findByOwnerUuidAndTeamSlotIsNotNullOrderByTeamSlot(UUID ownerUuid);

	Optional<Pokemon> findByOwnerUuidAndTeamSlot(UUID ownerUuid, Short teamSlot);

	Optional<Pokemon> findByOwnerUuidAndBoxIdAndBoxSlot(UUID ownerUuid, Short boxId, Short boxSlot);
}
