package com.mystaria.phantasmon_backend.battle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mystaria.phantasmon_backend.common.ApiException;
import com.mystaria.phantasmon_backend.pokemon.PokemonLegalityService;
import com.mystaria.phantasmon_backend.pokemon.PokemonResponse;

/**
 * Reads the {@code team} / {@code party} fields of {@code BattleInvite} and {@code BattleInviteResponse}. Absent or
 * {@code "GHOST"}: the Ghost active team. {@code "COBBLEMON"}: a copy of the player's real Cobblemon party, 1 to 6
 * members in the shape of a Ghost Pokémon. The copy comes from the player's own client, so it gets the same bounds and
 * legality rules as a Ghost (IV/EV ranges and total, nickname, data size — {@link PokemonLegalityService}); anything
 * else is refused with {@code ERROR_BATTLE_INVALID_PARTY}. Like the host's engine (LIM-1), a modified client could still
 * claim a party it doesn't own: nothing here can check the Minecraft server's real data.
 */
@Component
public class CobblemonPartyParser {

	static final int MAX_PARTY = 6;
	private static final int MAX_ID = 64;
	private static final int MAX_SHORT = 32;

	/** Thrown for any refused team; the caller answers {@code ERROR_BATTLE_INVALID_PARTY}. */
	public static final class InvalidPartyException extends RuntimeException {
		InvalidPartyException(String reason) {
			super(reason, null, false, false);
		}
	}

	private final PokemonLegalityService legalityService;

	public CobblemonPartyParser(PokemonLegalityService legalityService) {
		this.legalityService = legalityService;
	}

	public BattleTeam parse(UUID ownerUuid, Map<String, Object> message) {
		Object team = message.get("team");
		if (team == null || "GHOST".equals(team)) {
			return BattleTeam.GHOST;
		}
		if (!"COBBLEMON".equals(team) || !(message.get("party") instanceof List<?> members)
				|| members.isEmpty() || members.size() > MAX_PARTY) {
			throw new InvalidPartyException("team");
		}
		List<PokemonResponse> party = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (Object raw : members) {
			if (!(raw instanceof Map<?, ?> member)) {
				throw new InvalidPartyException("member");
			}
			PokemonResponse pokemon = member(ownerUuid, member, party.size() + 1);
			if (!seen.add(pokemon.uuid())) {
				throw new InvalidPartyException("duplicate uuid");
			}
			party.add(pokemon);
		}
		return new BattleTeam(BattleTeam.Source.COBBLEMON, List.copyOf(party));
	}

	@SuppressWarnings("unchecked")
	private PokemonResponse member(UUID ownerUuid, Map<?, ?> member, int slot) {
		UUID uuid;
		try {
			uuid = UUID.fromString(String.valueOf(member.get("uuid")));
		} catch (IllegalArgumentException ex) {
			throw new InvalidPartyException("uuid");
		}
		String species = text(member.get("species"), MAX_ID, true);
		String form = text(member.get("form"), MAX_ID, false);
		String nature = text(member.get("nature"), MAX_SHORT, true);
		String ability = text(member.get("ability"), MAX_ID, true);
		String version = text(member.get("cobblemon_data_version"), MAX_SHORT, false);
		if (!(member.get("level") instanceof Number level) || level.intValue() < 1 || level.intValue() > 100) {
			throw new InvalidPartyException("level");
		}
		if (!(member.get("data") instanceof Map<?, ?> rawData)) {
			throw new InvalidPartyException("data");
		}
		Map<String, Object> data = (Map<String, Object>) rawData;
		try {
			legalityService.validate(data);
		} catch (ApiException ex) {
			throw new InvalidPartyException(ex.getErrorCode());
		}
		return new PokemonResponse(uuid, ownerUuid, species, form, level.intValue(), nature, ability,
				Boolean.TRUE.equals(member.get("is_shiny")), null, null, slot, version == null ? "?" : version, data);
	}

	private static String text(Object value, int max, boolean required) {
		if (value == null || (value instanceof String s && s.isBlank())) {
			if (required) {
				throw new InvalidPartyException("missing field");
			}
			return null;
		}
		if (!(value instanceof String s) || s.length() > max) {
			throw new InvalidPartyException("field");
		}
		return s;
	}
}
