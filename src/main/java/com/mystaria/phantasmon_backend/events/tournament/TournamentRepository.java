package com.mystaria.phantasmon_backend.events.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TournamentRepository extends JpaRepository<Tournament, UUID> {

	@Query("select t from Tournament t where lower(t.name) = lower(?1) and t.status not in ('FINISHED', 'CANCELLED')")
	Optional<Tournament> findOpenByName(String name);

	List<Tournament> findAllByOrderByCreatedAtDesc();
}
