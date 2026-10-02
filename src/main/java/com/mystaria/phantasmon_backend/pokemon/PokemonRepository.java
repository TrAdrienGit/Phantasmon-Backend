package com.mystaria.phantasmon_backend.pokemon;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PokemonRepository extends JpaRepository<Pokemon, UUID> {

	List<Pokemon> findByOwnerUuid(UUID ownerUuid);

	List<Pokemon> findByOwnerUuidAndBoxId(UUID ownerUuid, Short boxId);

	List<Pokemon> findByOwnerUuidAndTeamSlotIsNotNullOrderByTeamSlot(UUID ownerUuid);

	Optional<Pokemon> findByOwnerUuidAndTeamSlot(UUID ownerUuid, Short teamSlot);

	Optional<Pokemon> findByOwnerUuidAndBoxIdAndBoxSlot(UUID ownerUuid, Short boxId, Short boxSlot);

	/**
	 * Native on purpose: keeps this package free of any dependency on the
	 * {@code trade} entities (the {@code trade} package already depends on
	 * {@code pokemon}, not the other way round). Replaces the old SQL
	 * {@code ON DELETE RESTRICT} FK dropped by {@code V7}.
	 */
	@Query(value = "SELECT EXISTS (SELECT 1 FROM trades WHERE status = 'PENDING' "
			+ "AND (offered_pokemon = :pokemonUuid OR requested_pokemon = :pokemonUuid))", nativeQuery = true)
	boolean isEngagedInPendingTrade(@Param("pokemonUuid") UUID pokemonUuid);
}
