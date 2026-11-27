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

	/**
	 * What a team member is, in Showdown ids' terms. {@code preTera}: terastallized from the start of the battle
	 * (2026-11-24), which takes Terastallization from the rest of its team.
	 */
	public record Member(String species, String form, String ability, String item, List<String> moves, String nickname,
			boolean preTera) {

		public Member(String species, String form, String ability, String item, List<String> moves, String nickname) {
			this(species, form, ability, item, moves, nickname, false);
		}
	}

	/**
	 * One broken rule: {@code code} (BANNED, NONSTANDARD, CLAUSE, TEAM_SIZE, LITTLE_CUP, SAME_TYPE, PRE_TERA_TWICE,
	 * PRE_TERA_ITEM) and what it's about.
	 */
	public record Issue(String code, String subject) {
	}

	private static final Set<String> EVASION_ITEMS = Set.of("brightpowder", "laxincense");
	private static final Set<String> EVASION_ABILITIES = Set.of("sandveil", "snowcloak");
	/** Tier tags matched against a species' doubles tier rather than its singles one. */
	private static final Set<String> DOUBLES_TAGS = Set.of("duber", "dou", "dbl", "duu", "dnu");
	/** Sleep moves the Gravity Sleep Clause bans alongside Gravity. */
	private static final Set<String> GRAVITY_SLEEP_MOVES = Set.of("darkvoid", "grasswhistle", "hypnosis", "lovelykiss",
			"sing", "sleeppowder");

	private final ShowdownData data;
	/** Validators reading a mod's data (Champions), by mod. */
	private final Map<String, TeamValidator> modded = new java.util.concurrent.ConcurrentHashMap<>();

	public TeamValidator(ShowdownData data) {
		this.data = data;
	}

	/** One list of issues per member, in team order (empty = fine). Nothing for the "Libre" format. */
	public List<List<Issue>> validate(BattleFormats.Format format, List<Member> team) {
		if (format != null && format.mod() != null && data.forMod(format.mod()) != data) {
			return modded.computeIfAbsent(format.mod(), mod -> new TeamValidator(data.forMod(mod))).validate(format, team);
		}
		List<List<Issue>> issues = new ArrayList<>();
		team.forEach(member -> issues.add(new ArrayList<>()));
		checkPreTera(format, team, issues); // whatever the format: the engine allows one Terastallization a battle
		if (format == null || format.free() || format.randomTeam()) {
			return issues; // random formats: Showdown generates the teams, the players' own are not used
		}
		Map<String, Integer> firstOfSpecies = new HashMap<>();
		Map<String, Integer> abilityCounts = new HashMap<>();
		Map<String, Integer> firstOfNickname = new HashMap<>();
		Map<String, Integer> itemCounts = new HashMap<>();
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
			checkMegaStoneUser(species, item, own);
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
			if (format.has("notfullyevolved") && species != null && species.evos().isEmpty()) {
				own.add(new Issue("CLAUSE", "Not Fully Evolved"));
			}
			// Ability Clause = n (Almost Any Ability): at most n Pokémon with the same ability.
			String abilityId = toId(member.ability());
			if (format.has("abilityclause") && !abilityId.isEmpty()
					&& abilityCounts.merge(abilityId, 1, Integer::sum) > format.values().getOrDefault("abilityclause", 1)) {
				own.add(new Issue("CLAUSE", "Ability Clause"));
			}
			if (format.has("speciesclause") && species != null) {
				Integer first = firstOfSpecies.putIfAbsent(species.baseSpeciesId(), i);
				if (first != null) {
					own.add(new Issue("CLAUSE", "Species Clause"));
				}
			}
			// Item Clause = n: at most n Pokémon holding the same item (Champions: 1).
			String itemId = toId(member.item());
			if (format.has("itemclause") && !itemId.isEmpty()
					&& itemCounts.merge(itemId, 1, Integer::sum) > format.values().getOrDefault("itemclause", 1)) {
				own.add(new Issue("CLAUSE", "Item Clause"));
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
		if (format.has("samecolorclause")) {
			checkSameTrait(team, issues, species -> species.color() == null ? Set.of() : Set.of(species.color()), "Same Color Clause");
		}
		if (format.has("sameletterclause")) {
			checkSameTrait(team, issues, species -> species.name().isEmpty() ? Set.of()
					: Set.of(species.name().substring(0, 1).toUpperCase(java.util.Locale.ROOT)), "Same Letter Clause");
		}
		int restrictedLimit = format.has("limitonerestricted") ? 1 : format.has("limittworestricted") ? 2 : -1;
		if (restrictedLimit >= 0) {
			checkRestricted(format, team, issues, restrictedLimit);
		}
		if (format.has("gravitysleepclause")) {
			checkGravitySleep(team, issues);
		}
		issues.replaceAll(list -> new ArrayList<>(new java.util.LinkedHashSet<>(list)));
		return issues;
	}

	/**
	 * Gravity Sleep Clause (doubles): no team with Gravity and a sleep move that can miss (Showdown's banlist of the
	 * rule). Every member holding either is flagged.
	 */
	private static void checkGravitySleep(List<Member> team, List<List<Issue>> issues) {
		boolean gravity = team.stream().anyMatch(member -> member.moves().stream().anyMatch(move -> "gravity".equals(toId(move))));
		boolean sleep = team.stream().anyMatch(member -> member.moves().stream().anyMatch(move -> GRAVITY_SLEEP_MOVES.contains(toId(move))));
		if (!gravity || !sleep) {
			return;
		}
		for (int i = 0; i < team.size(); i++) {
			if (team.get(i).moves().stream().map(ShowdownData::toId)
					.anyMatch(move -> "gravity".equals(move) || GRAVITY_SLEEP_MOVES.contains(move))) {
				issues.get(i).add(new Issue("CLAUSE", "Gravity Sleep Clause"));
			}
		}
	}

	/**
	 * Terastallized from the start: one per team, none holding what stops a Terastallization in Showdown (a Z-Crystal,
	 * or a Mega Stone the Pokémon can use). Allowed under the Terastal Clause, which it bypasses (2026-11-24).
	 */
	private void checkPreTera(BattleFormats.Format format, List<Member> team, List<List<Issue>> issues) {
		boolean seen = false;
		for (int i = 0; i < team.size(); i++) {
			Member member = team.get(i);
			if (!member.preTera()) {
				continue;
			}
			List<Issue> own = issues.get(i);
			if (seen) {
				own.add(new Issue("PRE_TERA_TWICE", ""));
			}
			seen = true;
			if (format != null && format.champions()) {
				own.add(new Issue("CLAUSE", "Terastal Clause")); // Champions has no Terastallization at all
			}
			String itemId = toId(member.item());
			ShowdownData.EntryDef item = itemId.isEmpty() ? null : data.item(itemId);
			ShowdownData.SpeciesDef species = species(member);
			boolean zCrystal = itemId.endsWith("iumz");
			boolean megaStone = item != null && species != null && item.megaFormeFor(species.name()) != null;
			if (zCrystal || megaStone) {
				own.add(new Issue("PRE_TERA_ITEM", item != null ? item.name() : member.item()));
			}
		}
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
		// A species ban covers all its formes; "X-Base" only the base forme; a tag ban (Flat Rules: -Mythical,
		// -Restricted Legendary) every species with that tag.
		boolean speciesBan = format.bans().contains(species.id())
				|| format.bans().contains(species.baseSpeciesId())
				|| species.tags().stream().anyMatch(tag -> format.bans().contains(ShowdownData.toId(tag)))
				|| species.id().equals(species.baseSpeciesId()) && format.bans().contains(species.id() + "base");
		if (speciesBan) {
			issues.add(new Issue("BANNED", species.name()));
			return;
		}
		String tier = toId(species.tier());
		String natDexTier = toId(species.natDexTier());
		// Doubles tags (Showdown's tags.ts): DUber, DOU, DBL, DUU, and DNU for "(DUU)".
		String doublesTier = "(DUU)".equals(species.doublesTier()) ? "dnu" : toId(species.doublesTier());
		for (String ban : format.bans()) {
			boolean natDex = ban.startsWith("nd");
			String tag = natDex ? ban.substring(2) : ban;
			String speciesTier = natDex ? natDexTier : DOUBLES_TAGS.contains(tag) ? doublesTier : tier;
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

	/** A Mega Stone only goes to its own species (Showdown's {@code itemUser}): Clefablite on Clefable, not on Pikachu. */
	private void checkMegaStoneUser(ShowdownData.SpeciesDef species, ShowdownData.EntryDef item, List<Issue> issues) {
		if (species == null || item == null || item.megaStone().isEmpty()) {
			return;
		}
		if (!item.usableBy(species.id())) {
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

	/**
	 * Limit One / Two Restricted (VGC, BSS): at most that many restricted Pokémon (Restricted Legendaries); the ones
	 * past the limit are flagged.
	 */
	private void checkRestricted(BattleFormats.Format format, List<Member> team, List<List<Issue>> issues, int limit) {
		int count = 0;
		for (int i = 0; i < team.size(); i++) {
			ShowdownData.SpeciesDef species = species(team.get(i));
			if (species == null) {
				continue;
			}
			boolean restricted = format.restricted().contains(species.id())
					|| format.restricted().contains(species.baseSpeciesId())
					|| species.tags().stream().anyMatch(tag -> format.restricted().contains(toId(tag)));
			if (restricted && ++count > limit) {
				issues.get(i).add(new Issue("CLAUSE", limit == 1 ? "Limit One Restricted" : "Limit Two Restricted"));
			}
		}
	}

	/**
	 * Same Color / Same Letter Clause: every member shares the trait — the members without the team's most common one
	 * are flagged.
	 */
	private void checkSameTrait(List<Member> team, List<List<Issue>> issues,
			java.util.function.Function<ShowdownData.SpeciesDef, Set<String>> trait, String clause) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		List<Set<String>> traits = new ArrayList<>();
		for (Member member : team) {
			ShowdownData.SpeciesDef species = species(member);
			Set<String> own = species == null ? Set.of() : trait.apply(species);
			traits.add(own);
			own.forEach(value -> counts.merge(value, 1, Integer::sum));
		}
		String best = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		if (best == null || counts.get(best) == team.size()) {
			return;
		}
		for (int i = 0; i < team.size(); i++) {
			if (!traits.get(i).contains(best)) {
				issues.get(i).add(new Issue("CLAUSE", clause));
			}
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
