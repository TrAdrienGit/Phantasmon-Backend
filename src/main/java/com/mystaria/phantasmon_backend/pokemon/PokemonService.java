package com.mystaria.phantasmon_backend.pokemon;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private static final int SLOTS_PER_BOX = 30;

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
		if (request.nature() != null) {
			pokemon.setNature(request.nature());
		}
		if (request.ability() != null) {
			pokemon.setAbility(request.ability());
		}
		if (request.isShiny() != null) {
			pokemon.setShiny(request.isShiny());
		}
		if (request.teamSlot() != null) {
			moveToTeamSlot(ownerUuid, pokemon, request.teamSlot().shortValue());
		} else if (request.boxId() != null || request.boxSlot() != null) {
			if (request.boxId() == null || request.boxSlot() == null) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ERROR_POKEMON_INCOMPLETE_BOX_DESTINATION", Map.of());
			}
			moveToPcSlot(ownerUuid, pokemon, request.boxId().shortValue(), request.boxSlot().shortValue());
		}

		try {
			pokemonRepository.saveAndFlush(pokemon);
		} catch (DataIntegrityViolationException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_POKEMON_SLOT_OCCUPIED", Map.of());
		}

		return PokemonResponse.from(pokemon);
	}

	/**
	 * Moves a Pokémon into a team slot, or {@link #moveToPcSlot} into a PC box
	 * slot — PC and team are mutually exclusive storage (CAD Partie 1 §12/§17),
	 * so either move always clears whichever kind of location the Pokémon
	 * doesn't end up in. Uniform drag&drop semantics across every combination
	 * (PC→PC, PC→team, team→PC, team→team — Adrien 2026-09-27): an empty
	 * destination is a plain move; an occupied destination **swaps** the two
	 * Pokémon's locations, regardless of whether either side is currently in
	 * the PC or the team. There is no automatic first-free-slot fallback here
	 * (unlike {@link #create}) — the player always names the exact destination.
	 */
	private void moveToTeamSlot(UUID ownerUuid, Pokemon pokemon, short teamSlot) {
		Optional<Pokemon> occupant = pokemonRepository.findByOwnerUuidAndTeamSlot(ownerUuid, teamSlot)
				.filter(candidate -> !candidate.getUuid().equals(pokemon.getUuid()));
		swapOrMove(pokemon, occupant, () -> {
			pokemon.setTeamSlot(teamSlot);
			pokemon.setBoxId(null);
			pokemon.setBoxSlot(null);
		});
	}

	private void moveToPcSlot(UUID ownerUuid, Pokemon pokemon, short boxId, short boxSlot) {
		Optional<Pokemon> occupant = pokemonRepository.findByOwnerUuidAndBoxIdAndBoxSlot(ownerUuid, boxId, boxSlot)
				.filter(candidate -> !candidate.getUuid().equals(pokemon.getUuid()));
		swapOrMove(pokemon, occupant, () -> {
			pokemon.setBoxId(boxId);
			pokemon.setBoxSlot(boxSlot);
			pokemon.setTeamSlot(null);
		});
	}

	/**
	 * If {@code occupant} is empty, just applies {@code applyNewLocation}. If
	 * an occupant is present, the two Pokémon trade locations: {@code pokemon}
	 * gets its new location ({@code applyNewLocation}), {@code occupant} gets
	 * {@code pokemon}'s *old* one (whatever mix of team_slot/box_id/box_slot
	 * that was). {@code pokemon}'s old location is cleared and flushed
	 * *before* handing it to the occupant — otherwise, for the brief moment
	 * between the occupant's write and pokemon's own (unflushed) write, two
	 * rows would share the same slot and trip the unique index (hit as a real
	 * bug while first writing the team-to-team swap test, 2026-09-27).
	 */
	private void swapOrMove(Pokemon pokemon, Optional<Pokemon> occupant, Runnable applyNewLocation) {
		occupant.ifPresentOrElse(other -> {
			Short oldTeamSlot = pokemon.getTeamSlot();
			Short oldBoxId = pokemon.getBoxId();
			Short oldBoxSlot = pokemon.getBoxSlot();

			pokemon.setTeamSlot(null);
			pokemon.setBoxId(null);
			pokemon.setBoxSlot(null);
			pokemonRepository.saveAndFlush(pokemon);

			other.setTeamSlot(oldTeamSlot);
			other.setBoxId(oldBoxId);
			other.setBoxSlot(oldBoxSlot);
			pokemonRepository.saveAndFlush(other);

			applyNewLocation.run();
		}, applyNewLocation);
	}

	@Transactional
	public void delete(UUID ownerUuid, UUID pokemonUuid) {
		Pokemon pokemon = findOwned(ownerUuid, pokemonUuid);
		if (pokemonRepository.isEngagedInPendingTrade(pokemonUuid)) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_POKEMON_IN_PENDING_TRADE", Map.of("uuid", pokemonUuid));
		}
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

	/**
	 * Swaps two team members between their owners, each one taking the exact
	 * team slot the other leaves (live trade screen, Adrien 2026-10-02 — like a
	 * Cobblemon party trade, so it can never fail on a full PC the way
	 * {@link #transferOwnership} can). Both must currently be in a team;
	 * ownership/team checks are the caller's job. Same "vacate, flush, then
	 * occupy" ordering as {@link #swapOrMove}: both team slots are cleared and
	 * flushed before either Pokémon claims its new one, so the per-owner
	 * unique team-slot index never sees two rows on the same slot.
	 */
	@Transactional
	public void swapTeamMembersBetweenOwners(UUID firstPokemonUuid, UUID secondPokemonUuid) {
		Pokemon first = pokemonRepository.findById(firstPokemonUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND", Map.of("uuid", firstPokemonUuid)));
		Pokemon second = pokemonRepository.findById(secondPokemonUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND", Map.of("uuid", secondPokemonUuid)));
		UUID firstOwner = first.getOwnerUuid();
		UUID secondOwner = second.getOwnerUuid();
		Short firstSlot = first.getTeamSlot();
		Short secondSlot = second.getTeamSlot();

		first.setTeamSlot(null);
		second.setTeamSlot(null);
		pokemonRepository.saveAndFlush(first);
		pokemonRepository.saveAndFlush(second);

		first.setOwnerUuid(secondOwner);
		first.setTeamSlot(secondSlot);
		first.setBoxId(null);
		first.setBoxSlot(null);
		second.setOwnerUuid(firstOwner);
		second.setTeamSlot(firstSlot);
		second.setBoxId(null);
		second.setBoxSlot(null);
		pokemonRepository.saveAndFlush(first);
		pokemonRepository.saveAndFlush(second);
	}

	@Transactional(readOnly = true)
	public List<PokemonResponse> activeTeam(UUID ownerUuid) {
		return pokemonRepository.findByOwnerUuidAndTeamSlotIsNotNullOrderByTeamSlot(ownerUuid).stream()
				.map(PokemonResponse::from).toList();
	}

	private Pokemon findOwned(UUID ownerUuid, UUID pokemonUuid) {
		Pokemon pokemon = pokemonRepository.findById(pokemonUuid)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ERROR_POKEMON_NOT_FOUND", Map.of("uuid", pokemonUuid)));
		if (!pokemon.getOwnerUuid().equals(ownerUuid)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ERROR_OWNERSHIP_MISMATCH", Map.of("uuid", pokemonUuid));
		}
		return pokemon;
	}

	/** Whether {@code ownerUuid} has at least one free PC slot (never throws, unlike {@link #transferOwnership}). */
	@Transactional(readOnly = true)
	public boolean hasFreePcSlot(UUID ownerUuid) {
		return pokemonRepository.findByOwnerUuid(ownerUuid).stream()
				.filter(p -> p.getBoxId() != null && p.getBoxSlot() != null)
				.count() < (long) BOX_COUNT * SLOTS_PER_BOX;
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
