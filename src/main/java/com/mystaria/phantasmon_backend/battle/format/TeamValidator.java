package com.mystaria.phantasmon_backend.battle.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mystaria.phantasmon_backend.battle.format.ShowdownData.toId;

/**
 * Checks a team against a {@link BattleFormats.Format} (TODO-24): which of its Pokémon break which rule, so the
 * lobby can circle them in red and refuse "ready". A subset of Showdown's team validator — what applies to our
 * teams: non-standard species / items / moves / abilities (e.g. Past in Gen 9), bans (species, tiers, items,
 * abilities, moves), Species / Nickname / OHKO / Evasion / Sleep Moves / Accuracy Moves / Same Type clauses, Little
 * Cup, team size. Movesets themselves are not re-checked against learnsets.
 */
public final class TeamValidator {

	/** What a team member is, in Showdown ids' terms. */
	public record Member(String species, String form, String ability, String item, List<String> moves, String nickname) {
	}

	/** One broken rule: {@code code} (BANNED, NONSTANDARD, CLAUSE, TEAM_SIZE, LITTLE_CUP, SAME_TYPE) and what it's about. */
	public record Issue(String code, String subject) {
	}

	private static final Set<String> EVASION_ITEMS = Set.of("brightpowder", "laxincense");
	private static final Set<String> EVASION_ABILITIES = Set.of("sandveil", "snowcloak");

	private final ShowdownData data;

	public TeamValidator(ShowdownData data) {
		this.data = data;
	}

	/** One list of issues per member, in team order (empty = fine). Nothing for the "Libre" format. */
	public List<List<Issue>> validate(BattleFormats.Format format, List<Member> team) {
		List<List<Issue>> issues = new ArrayList<>();
		team.forEach(member -> issues.add(new ArrayList<>()));
		if (format == null || format.free()) {
			return issues;
		}
		Map<String, Integer> firstOfSpecies = new HashMap<>();
		Map<String, Integer> firstOfNickname = new HashMap<>();
		for (int i = 0; i < team.size(); i++) {
			Member member = team.get(i);
			List<Issue> own = issues.get(i);
			ShowdownData.SpeciesDef species = species(member);

			if (i >= format.maxTeamSize()) {
				own.add(new Issue("TEAM_SIZE", String.valueOf(format.maxTeamSize())));
			}
			checkSpecies(format, species, own);
			ShowdownData.EntryDef item = data.item(toId(member.item()));
			checkEntry(format, item, own);
			checkMegaStone(format, species, item, own);
			checkEntry(format, data.ability(toId(member.ability())), own);
			for (String move : member.moves()) {
				checkMove(format, data.move(toId(move)), own);
			}
			if (format.has("evasionitemsclause") || format.has("evasionclause")) {
				if (EVASION_ITEMS.contains(toId(member.item()))) {
					own.add(new Issue("CLAUSE", "Evasion Items Clause"));
				}
			}
			if (format.has("evasionabilitiesclause") || format.has("evasionclause")) {
				if (EVASION_ABILITIES.contains(toId(member.ability()))) {
					own.add(new Issue("CLAUSE", "Evasion Abilities Clause"));
				}
			}
			if (format.has("littlecup") && species != null && (species.prevo() != null || species.evos().isEmpty())) {
				own.add(new Issue("LITTLE_CUP", species.name()));
			}
			if (format.has("speciesclause") && species != null) {
				Integer first = firstOfSpecies.putIfAbsent(species.baseSpeciesId(), i);
				if (first != null) {
					own.add(new Issue("CLAUSE", "Species Clause"));
				}
			}
			String nickname = toId(member.nickname());
			if (format.has("nicknameclause") && !nickname.isEmpty()) {
				Integer first = firstOfNickname.putIfAbsent(nickname, i);
				if (first != null) {
					own.add(new Issue("CLAUSE", "Nickname Clause"));
				}
			}
		}
		if (format.has("sametypeclause")) {
			checkSameType(team, issues);
		}
		issues.replaceAll(list -> new ArrayList<>(new java.util.LinkedHashSet<>(list)));
		return issues;
	}

	private ShowdownData.SpeciesDef species(Member member) {
		ShowdownData.SpeciesDef withForm = member.form() == null || member.form().isBlank() ? null
				: data.species(toId(member.species()) + toId(member.form()));
		return withForm != null ? withForm : data.species(toId(member.species()));
	}

	private void checkSpecies(BattleFormats.Format format, ShowdownData.SpeciesDef species, List<Issue> issues) {
		if (species == null) {
			return; // unknown to Showdown (custom Cobblemon species): nothing to check against
		}
		if (unbanned(format, species.id()) || unbanned(format, species.baseSpeciesId())) {
			return;
		}
		if (nonstandardBanned(format, species.isNonstandard())) {
			issues.add(new Issue("NONSTANDARD", species.name()));
		}
		if (format.has("natdexmod") && "Illegal".equals(species.natDexTier())) {
			issues.add(new Issue("NONSTANDARD", species.name()));
		}
		// A species ban covers all its formes; "X-Base" only the base forme.
		boolean speciesBan = format.bans().contains(species.id())
				|| format.bans().contains(species.baseSpeciesId())
				|| species.id().equals(species.baseSpeciesId()) && format.bans().contains(species.id() + "base");
		if (speciesBan) {
			issues.add(new Issue("BANNED", species.name()));
			return;
		}
		String tier = toId(species.tier());
		String natDexTier = toId(species.natDexTier());
		for (String ban : format.bans()) {
			boolean natDex = ban.startsWith("nd");
			String tag = natDex ? ban.substring(2) : ban;
			String speciesTier = natDex ? natDexTier : tier;
			if (!tag.isEmpty() && tag.equals(speciesTier)) {
				issues.add(new Issue("BANNED", species.name()));
				return;
			}
		}
	}

	/**
	 * A Mega Stone is banned when the Mega forme it gives this Pokémon is (Showdown checks the stone through that
	 * forme): Gengarite in National Dex because Gengar-Mega is ND AG, Lucarionite because Lucario-Mega is ND Uber.
	 */
	private void checkMegaStone(BattleFormats.Format format, ShowdownData.SpeciesDef species, ShowdownData.EntryDef item,
			List<Issue> issues) {
		if (species == null || item == null || unbanned(format, item.id())) {
			return;
		}
		String forme = item.megaFormeFor(species.name());
		ShowdownData.SpeciesDef mega = forme == null ? null : data.species(toId(forme));
		if (mega == null || unbanned(format, mega.id())) {
			return;
		}
		List<Issue> megaIssues = new ArrayList<>();
		checkSpecies(format, mega, megaIssues);
		if (megaIssues.stream().anyMatch(issue -> "BANNED".equals(issue.code()))) {
			issues.add(new Issue("BANNED", item.name()));
		}
	}

	private void checkEntry(BattleFormats.Format format, ShowdownData.EntryDef entry, List<Issue> issues) {
		if (entry == null || unbanned(format, entry.id())) {
			return;
		}
		if (nonstandardBanned(format, entry.isNonstandard())) {
			issues.add(new Issue("NONSTANDARD", entry.name()));
		} else if (format.bans().contains(entry.id())) {
			issues.add(new Issue("BANNED", entry.name()));
		}
	}

	private void checkMove(BattleFormats.Format format, ShowdownData.EntryDef move, List<Issue> issues) {
		if (move == null) {
			return;
		}
		checkEntry(format, move, issues);
		if (format.has("ohkoclause") && move.ohko()) {
			issues.add(new Issue("CLAUSE", "OHKO Clause"));
		}
		if ((format.has("evasionmovesclause") || format.has("evasionclause")) && move.evasionBoost() > 0) {
			issues.add(new Issue("CLAUSE", "Evasion Moves Clause"));
		}
		if (format.has("sleepmovesclause") && ("slp".equals(move.status()) || "yawn".equals(move.id()))) {
			issues.add(new Issue("CLAUSE", "Sleep Moves Clause"));
		}
		if (format.has("accuracymovesclause") && move.accuracyBoost() < 0 && !move.selfTarget()) {
			issues.add(new Issue("CLAUSE", "Accuracy Moves Clause"));
		}
	}

	/** Monotype: every member shares a type — the members without the team's most common type are flagged. */
	private void checkSameType(List<Member> team, List<List<Issue>> issues) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		List<Set<String>> types = new ArrayList<>();
		for (Member member : team) {
			ShowdownData.SpeciesDef species = species(member);
			Set<String> own = species == null ? Set.of() : new HashSet<>(species.types());
			types.add(own);
			own.forEach(type -> counts.merge(type, 1, Integer::sum));
		}
		String best = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		if (best == null || counts.get(best) == team.size()) {
			return;
		}
		for (int i = 0; i < team.size(); i++) {
			if (!types.get(i).contains(best)) {
				issues.get(i).add(new Issue("SAME_TYPE", best));
			}
		}
	}

	/** Showdown bans anything non-standard (Past, Unobtainable, CAP...) unless the format unbans that tag (+Past...). */
	private static boolean nonstandardBanned(BattleFormats.Format format, String isNonstandard) {
		return isNonstandard != null && !format.unbans().contains(toId(isNonstandard));
	}

	private static boolean unbanned(BattleFormats.Format format, String id) {
		return format.unbans().contains(id);
	}
}
