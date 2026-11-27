package com.mystaria.phantasmon_backend.battle.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mystaria.phantasmon_backend.battle.format.ShowdownData.toId;

/**
 * The battle formats offered in the lobby (TODO-24, Adrien 2026-10-06): "Libre" (no rule, the default) plus 19
 * Smogon singles formats, National Dex first, then the Pokémon Champions singles formats (2026-11-27), then the doubles
 * formats (combat-double-plan.md): each format keeps Showdown's {@code gameType}, a doubles one starting the hosted
 * battle in doubles with two leads. Champions
 * formats check teams with Champions' own rules and lists (Showdown's {@code champions} mod, see
 * {@link ShowdownData#forMod}); the battle itself stays Cobblemon's Gen 9 engine, with Terastallization off as in
 * Champions. Each one's rules and bans are resolved from Showdown's own definitions
 * ({@link ShowdownData}), the way Showdown's rule table does: composite rules expand, a parent format named in a
 * ruleset is inherited, {@code !Rule} removes a rule, {@code +X} / {@code -X} unban / ban, {@code Rule = n} sets a
 * value.
 */
public final class BattleFormats {

	public static final String FREE = "free";

	/** Offered formats, in lobby order: id (Showdown's) → fallback name (Showdown's name wins when known). */
	public static final List<String> OFFERED = List.of(FREE,
			"gen9nationaldex", "gen9nationaldexubers", "gen9nationaldexuu", "gen9nationaldexru",
			"gen9nationaldexmonotype", "gen9nationaldexlc", "gen9nationaldex1v1", "gen9nationaldexag",
			"gen9ou", "gen9ubers", "gen9uu", "gen9ru", "gen9nu", "gen9pu", "gen9zu", "gen9lc", "gen9monotype",
			"gen91v1", "gen9anythinggoes",
			"gen9championsou", "gen9championsuu", "gen9championsnationaldex",
			"gen9doublesou", "gen9doublesubers", "gen9doublesuu", "gen9nationaldexdoubles", "gen9championsdoubles", "gen9championsvgc2026regmc");

	/** Showdown's game types: one Pokémon per side, or two. */
	public static final String SINGLES = "singles";
	public static final String DOUBLES = "doubles";

	/** The Showdown mod of the Pokémon Champions formats. */
	public static final String CHAMPIONS = "champions";

	/** Rules Showdown's battle engine itself applies (the others only check teams): passed to the hosted battle. */
	private static final Set<String> BATTLE_RULES = Set.of("sleepclausemod", "freezeclausemod", "endlessbattleclause",
			"terastalclause", "megarayquazaclause", "zmoveclause", "dynamaxclause");

	/** A resolved format. {@code rules}: rule ids; {@code bans} / {@code unbans}: ids of species, tags, items, moves... */
	public record Format(String id, String name, String mod, String gameType, Set<String> rules, Set<String> bans,
			Set<String> unbans, Map<String, Integer> values, List<String> battleRules) {

		/** Two Pokémon per side: two leads in the lobby, a doubles battle. */
		public boolean doubles() {
			return DOUBLES.equals(gameType);
		}

		/** Pokémon each side starts the battle with. */
		public int activeCount() {
			return doubles() ? 2 : 1;
		}

		/** A Pokémon Champions format: no Terastallization. */
		public boolean champions() {
			return CHAMPIONS.equals(mod);
		}

		public boolean free() {
			return FREE.equals(id);
		}

		public boolean has(String rule) {
			return rules.contains(rule);
		}

		/** Level every Pokémon is set to for the battle: 5 in Little Cup, else 100; 0 = their own (Libre). */
		public int adjustLevel() {
			if (free()) {
				return 0;
			}
			Integer level = values.get("adjustlevel");
			if (level != null) {
				return level;
			}
			return has("littlecup") ? 5 : 100;
		}

		/**
		 * Pokémon actually sent into the battle (1v1: one; VGC: 4 of 6), 0 = the whole team. "Picked Team Size = Auto"
		 * (Flat Rules) is 4 in doubles, 3 in singles, as in Showdown.
		 */
		public int pickedTeamSize() {
			Integer size = values.get("pickedteamsize");
			if (size != null) {
				return size;
			}
			return has("pickedteamsize") ? (doubles() ? 4 : 3) : 0;
		}

		public int maxTeamSize() {
			return values.getOrDefault("maxteamsize", 6);
		}
	}

	private final ShowdownData data;
	private final Map<String, Format> resolved = new HashMap<>();

	public BattleFormats(ShowdownData data) {
		this.data = data;
	}

	/** The offered format with this id, or null. */
	public Format get(String id) {
		if (id == null || !OFFERED.contains(id)) {
			return null;
		}
		return resolved.computeIfAbsent(id, this::resolve);
	}

	/** Any format of Showdown's data, offered or not (tests, future lobby entries). */
	Format resolveAny(String id) {
		return resolved.computeIfAbsent(id, this::resolve);
	}

	public List<Format> offered() {
		List<Format> formats = new ArrayList<>();
		for (String id : OFFERED) {
			Format format = get(id);
			if (format != null) {
				formats.add(format);
			}
		}
		return formats;
	}

	private Format resolve(String id) {
		if (FREE.equals(id)) {
			return new Format(FREE, "Libre", null, SINGLES, Set.of(), Set.of(), Set.of(), Map.of(), List.of());
		}
		ShowdownData.FormatDef def = data.format(id);
		if (def == null) {
			return null; // not in this Showdown data any more
		}
		// A format running on a mod (Champions) reads the mod's rules, tiers and lists: there the new Megas are standard.
		ShowdownData source = data.forMod(def.mod());
		Builder builder = new Builder(source);
		builder.addFormat(def, new HashSet<>());
		builder.rules.removeAll(builder.removed);
		if (CHAMPIONS.equals(def.mod())) {
			builder.rules.add("terastalclause"); // Champions has no Terastallization
		}
		List<String> battleRules = new ArrayList<>();
		for (String rule : builder.rules) {
			if (BATTLE_RULES.contains(rule)) {
				ShowdownData.RuleDef ruleDef = source.rule(rule);
				battleRules.add(ruleDef != null ? ruleDef.name() : rule);
			}
		}
		return new Format(id, def.name(), def.mod(), DOUBLES.equals(def.gameType()) ? DOUBLES : SINGLES,
				Set.copyOf(builder.rules), Set.copyOf(builder.bans),
				Set.copyOf(builder.unbans), Map.copyOf(builder.values), List.copyOf(battleRules));
	}

	private static final class Builder {
		final ShowdownData data;
		final Set<String> rules = new LinkedHashSet<>();
		final Set<String> removed = new HashSet<>();
		final Set<String> bans = new HashSet<>();
		final Set<String> unbans = new HashSet<>();
		final Map<String, Integer> values = new HashMap<>();

		Builder(ShowdownData data) {
			this.data = data;
		}

		void addFormat(ShowdownData.FormatDef def, Set<String> seen) {
			if (!seen.add(toId(def.name()))) {
				return;
			}
			def.ruleset().forEach(entry -> addEntry(entry, seen));
			def.banlist().forEach(entry -> ban(entry));
			def.unbanlist().forEach(entry -> unbans.add(toId(entry)));
		}

		void addEntry(String entry, Set<String> seen) {
			String trimmed = entry.trim();
			if (trimmed.startsWith("!")) {
				removed.add(toId(trimmed.substring(1)));
			} else if (trimmed.startsWith("+")) {
				unbans.add(toId(trimmed.substring(1)));
			} else if (trimmed.startsWith("-")) {
				ban(trimmed.substring(1));
			} else if (trimmed.startsWith("*")) {
				// restricted: only matters for formats with restricted lists, none offered
			} else if (trimmed.contains("=")) {
				String key = toId(trimmed.substring(0, trimmed.indexOf('=')));
				String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
				try {
					values.put(key, Integer.parseInt(value));
				} catch (NumberFormatException ex) {
					// "Auto" and other non-numeric values: not used here
				}
				rules.add(key);
			} else if (trimmed.startsWith("[")) {
				ShowdownData.FormatDef parent = data.format(toId(trimmed));
				if (parent != null) {
					addFormat(parent, seen);
				}
			} else {
				addRule(toId(trimmed), seen);
			}
		}

		void addRule(String rule, Set<String> seen) {
			rules.add(rule);
			ShowdownData.RuleDef def = data.rule(rule);
			if (def == null || !seen.add("rule:" + rule)) {
				return;
			}
			def.ruleset().forEach(entry -> addEntry(entry, seen));
			def.banlist().forEach(entry -> ban(entry));
			def.unbanlist().forEach(entry -> unbans.add(toId(entry)));
		}

		void ban(String entry) {
			if (entry.contains(" + ") || entry.contains(" ++ ")) {
				return; // combination bans ("A + B", team-wide "A ++ B"): not checked here
			}
			bans.add(toId(entry));
		}
	}
}
