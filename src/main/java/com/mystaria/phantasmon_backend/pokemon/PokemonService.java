package com.mystaria.phantasmon_backend.pokemon;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.common.ApiException;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PokemonService {

	private static final int BOX_COUNT = 16;
	private static final int SLOTS_PER_BOX = 36;

	private final PokemonRepository pokemonRepository;
	private final PokemonLegalityService legalityService;

	public PokemonService(PokemonRepository pokemonRepository, PokemonLegalityService legalityService) {
		this.pokemonRepository = pokemonRepository;
		this.legalityService = legalityService;
	}

	@Transactional
	public PokemonResponse create(UUID ownerUuid, PokemonCreateRequest request) {
		legalityService.validate(request.data());

		Short boxId = toShort(request.boxId());
		Short boxSlot = toShort(request.boxSlot());
		Short teamSlot = toShort(request.teamSlot());

		if (boxId == null && boxSlot == null && teamSlot == null) {
			int[] freeSlot = findFreePcSlot(ownerUuid);
			boxId = (short) freeSlot[0];
			boxSlot = (short) freeSlot[1];
		}

		Pokemon pokemon = new Pokemon(UUID.randomUUID(), ownerUuid, request.species(), request.form(),
				request.level().shortValue(), request.nature(), request.ability(),
				Boolean.TRUE.equals(request.isShiny()), boxId, boxSlot, teamSlot,
				request.cobblemonDataVersion(), request.data());

		try {
			pokemonRepository.saveAndFlush(pokemon);
		} catch (DataIntegrityViolationException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_POKEMON_SLOT_OCCUPIED", Map.of());
		}

		log.info("Pokemon {} ({}) created for owner {}", request.species(), pokemon.getUuid(), ownerUuid);
		return PokemonResponse.from(pokemon);
	}

	@Transactional(readOnly = true)
	public List<PokemonResponse> listForOwner(UUID ownerUuid) {
		return pokemonRepository.findByOwnerUuid(ownerUuid).stream().map(PokemonResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<PokemonResponse> pcBox(UUID ownerUuid, int box) {
		return pokemonRepository.findByOwnerUuidAndBoxId(ownerUuid, (short) box).stream()
				.map(PokemonResponse::from).toList();
	}

	@Transactional
	public PokemonResponse update(UUID ownerUuid, UUID pokemonUuid, PokemonUpdateRequest request) {
		Pokemon pokemon = findOwned(ownerUuid, pokemonUuid);

		if (request.data() != null) {
			legalityService.validate(request.data());
			pokemon.setData(request.data());
		}
		if (request.level() != null) {
			pokemon.setLevel(request.level().shortValue());
		}
		if (request.teamSlot() != null) {
			pokemon.setTeamSlot(request.teamSlot().shortValue());
		}

		try {
			pokemonRepository.saveAndFlush(pokemon);
		} catch (DataIntegrityViolationException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_POKEMON_SLOT_OCCUPIED", Map.of());
		}

		return PokemonResponse.from(pokemon);
	}

	@Transactional
	public void delete(UUID ownerUuid, UUID pokemonUuid) {
		Pokemon pokemon = findOwned(ownerUuid, pokemonUuid);
		pokemonRepository.delete(pokemon);
		log.info("Pokemon {} deleted by owner {}", pokemonUuid, ownerUuid);
	}

	@Transactional
	public PokemonResponse clone(UUID ownerUuid, UUID pokemonUuid) {
		Pokemon original = findOwned(ownerUuid, pokemonUuid);

		int[] freeSlot = findFreePcSlot(ownerUuid);
		Pokemon clone = new Pokemon(UUID.randomUUID(), ownerUuid, original.getSpecies(), original.getForm(),
				original.getLevel(), original.getNature(), original.getAbility(), original.isShiny(),
				(short) freeSlot[0], (short) freeSlot[1], null,
				original.getCobblemonDataVersion(), original.getData());

		pokemonRepository.saveAndFlush(clone);
		log.info("Pokemon {} cloned to {} for owner {}", pokemonUuid, clone.getUuid(), ownerUuid);
		return PokemonResponse.from(clone);
	}

	/**
	 * Transfers a Pokémon to a new owner, clearing its team slot and
	 * re-assigning it to the first free PC slot in the new owner's boxes —
	 * its old {@code box_id}/{@code box_slot} almost certainly collides with
	 * an existing Pokémon of the new owner (the unique PC-slot index is
	 * scoped per owner). Used by trade completion (CAD Partie 3 §D.2); joins
	 * the caller's existing transaction rather than starting its own, so a
	 * two-Pokémon swap stays atomic.
	 */
	@Transactional
	public void transferOwnership(UUID pokemonUuid, UUID newOwnerUuid) {
		Pokemon pokemon = pokemonRepository.findById(pokemonUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND", Map.of("uuid", pokemonUuid)));
		int[] freeSlot = findFreePcSlot(newOwnerUuid);
		pokemon.setOwnerUuid(newOwnerUuid);
		pokemon.setBoxId((short) freeSlot[0]);
		pokemon.setBoxSlot((short) freeSlot[1]);
		pokemon.setTeamSlot(null);
		pokemonRepository.saveAndFlush(pokemon);
	}

	private Pokemon findOwned(UUID ownerUuid, UUID pokemonUuid) {
		Pokemon pokemon = pokemonRepository.findById(pokemonUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND", Map.of("uuid", pokemonUuid)));
		if (!pokemon.getOwnerUuid().equals(ownerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", pokemonUuid));
		}
		return pokemon;
	}

	private int[] findFreePcSlot(UUID ownerUuid) {
		Set<String> occupied = pokemonRepository.findByOwnerUuid(ownerUuid).stream()
				.filter(p -> p.getBoxId() != null && p.getBoxSlot() != null)
				.map(p -> p.getBoxId() + ":" + p.getBoxSlot())
				.collect(Collectors.toSet());

		for (int box = 1; box <= BOX_COUNT; box++) {
			for (int slot = 1; slot <= SLOTS_PER_BOX; slot++) {
				if (!occupied.contains(box + ":" + slot)) {
					return new int[] { box, slot };
				}
			}
		}
		throw new ApiException(HttpStatus.CONFLICT, "ERROR_POKEMON_PC_FULL", Map.of());
	}

	private static Short toShort(Integer value) {
		return value == null ? null : value.shortValue();
	}
}
