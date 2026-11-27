package com.mystaria.phantasmon_backend.battle.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pokémon Showdown's public data (formats, tiers, Pokédex, items, moves, abilities, rule definitions), parsed into
 * what {@link BattleFormats} and {@link TeamValidator} need (TODO-24). The {@code play.pokemonshowdown.com/data/*.js}
 * files are JavaScript object literals with unquoted keys — Jackson reads them leniently once their
 * {@code exports.X = ... ;} wrapper is cut; {@code rulesets.ts} is source code, only the composite rules' lists are
 * read from it.
 *
 * <p>A format may run on a Showdown <em>mod</em> ({@code mod: "champions"}): the mod's own tiers, non-standard statuses
 * and rule changes ({@code data/mods/<mod>/*.ts} of the Showdown repository) are laid over the base data, and
 * {@link #forMod(String)} gives that modded view (2026-11-27, Champions formats).
 */
public final class ShowdownData {

	/** Showdown mods whose data is read: the formats running on them see it through {@link #forMod(String)}. */
	public static final List<String> MODS = List.of("champions");

	/** Files of one mod, under {@code mods/<mod>/}. */
	public static final List<String> MOD_FILES = List.of(
			"formats-data.ts", "items.ts", "moves.ts", "abilities.ts", "rulesets.ts");

	public static final List<String> FILES = files();

	private static List<String> files() {
		List<String> files = new ArrayList<>(List.of(
				"formats.js", "formats-data.js", "pokedex.js", "items.js", "moves.js", "abilities.js", "rulesets.ts"));
		for (String mod : MODS) {
			MOD_FILES.forEach(file -> files.add("mods/" + mod + "/" + file));
		}
		return List.copyOf(files);
	}

	private static final JsonMapper LENIENT = JsonMapper.builder()
			.enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
			.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
			.enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
			.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
			.build();

	/** A format of {@code formats.js}. */
	public record FormatDef(String name, String mod, String gameType, List<String> ruleset, List<String> banlist,
			List<String> unbanlist) {
	}

	/**
	 * A Pokémon species / forme of {@code pokedex.js} plus its tiers from {@code formats-data.js}. {@code doublesTier}
	 * falls back on {@code tier} when absent, as in Showdown. {@code tags}: "Mythical", "Restricted Legendary"...
	 */
	public record SpeciesDef(String id, String name, String baseSpeciesId, List<String> types, String prevo, List<String> evos,
			String isNonstandard, String tier, String natDexTier, String doublesTier, List<String> tags) {
	}

	/** An item, ability or move: its name, its non-standard status, and (moves) the flags the clauses look at. */
	public record EntryDef(String id, String name, String isNonstandard, boolean ohko, String status, int evasionBoost,
			int accuracyBoost, boolean selfTarget, Map<String, String> megaStone, List<String> itemUser) {

		/** Items: the Mega forme this stone gives {@code speciesName} (Showdown name), or null. */
		public String megaFormeFor(String speciesName) {
			if (megaStone.isEmpty()) {
				return null;
			}
			String forme = megaStone.get(speciesName);
			return forme != null ? forme : megaStone.size() == 1 && megaStone.containsKey("") ? megaStone.get("") : null;
		}

		/**
		 * Items: whether {@code speciesId} may hold this item, by Showdown's {@code itemUser} key (the species the item is
		 * bound to). True when the item has no such key.
		 */
		public boolean usableBy(String speciesId) {
			return itemUser.isEmpty() || itemUser.stream().anyMatch(user -> toId(user).equals(speciesId));
		}
	}

	/** A rule of {@code rulesets.ts}: its display name and the rules it is made of (empty for a plain rule). */
	public record RuleDef(String id, String name, List<String> ruleset, List<String> banlist, List<String> unbanlist) {
	}

	private final Map<String, FormatDef> formats;
	private final Map<String, SpeciesDef> species;
	private final Map<String, EntryDef> items;
	private final Map<String, EntryDef> moves;
	private final Map<String, EntryDef> abilities;
	private final Map<String, RuleDef> rules;
	private final Map<String, ShowdownData> modded = new HashMap<>();

	private ShowdownData(Map<String, FormatDef> formats, Map<String, SpeciesDef> species, Map<String, EntryDef> items,
			Map<String, EntryDef> moves, Map<String, EntryDef> abilities, Map<String, RuleDef> rules) {
		this.formats = formats;
		this.species = species;
		this.items = items;
		this.moves = moves;
		this.abilities = abilities;
		this.rules = rules;
	}

	/** {@code files}: file name → content, for every name of {@link #FILES}. */
	public static ShowdownData parse(Map<String, String> files) {
		Map<String, FormatDef> formats = new HashMap<>();
		for (JsonNode node : literal(files.get("formats.js"))) {
			if (node.has("name")) {
				String name = node.get("name").asString();
				formats.put(toId(name), new FormatDef(name, text(node, "mod"), text(node, "gameType"), strings(node.get("ruleset")),
						strings(node.get("banlist")), strings(node.get("unbanlist"))));
			}
		}

		JsonNode tiers = literal(files.get("formats-data.js"));
		Map<String, SpeciesDef> species = new HashMap<>();
		JsonNode dex = literal(files.get("pokedex.js"));
		for (Map.Entry<String, JsonNode> entry : dex.properties()) {
			JsonNode node = entry.getValue();
			JsonNode tier = tiers.get(entry.getKey());
			String nonstandard = text(node, "isNonstandard");
			if (nonstandard == null && tier != null) {
				nonstandard = text(tier, "isNonstandard");
			}
			String baseSpecies = text(node, "baseSpecies");
			species.put(entry.getKey(), new SpeciesDef(entry.getKey(), text(node, "name"),
					baseSpecies == null ? entry.getKey() : toId(baseSpecies), strings(node.get("types")),
					text(node, "prevo"), strings(node.get("evos")), nonstandard,
					tier == null ? null : text(tier, "tier"), tier == null ? null : text(tier, "natDexTier"),
					tier == null ? null : orElse(text(tier, "doublesTier"), text(tier, "tier")), strings(node.get("tags"))));
		}

		ShowdownData base = new ShowdownData(formats, species, entries(files.get("items.js")), entries(files.get("moves.js")),
				entries(files.get("abilities.js")), parseRules(files.get("rulesets.ts")));
		for (String mod : MODS) {
			String prefix = "mods/" + mod + "/";
			if (MOD_FILES.stream().allMatch(file -> files.get(prefix + file) != null)) {
				base.modded.put(mod, base.withMod(file -> files.get(prefix + file)));
			}
		}
		return base;
	}

	/**
	 * This data as a mod sees it. A species listed in the mod's {@code formats-data.ts} takes its tiers and
	 * non-standard status from there (a mod lists every species, absent fields being none); an item, move or ability
	 * the mod lists keeps everything but the {@code isNonstandard} it sets; a rule the mod redefines takes its
	 * {@code ruleset} / {@code banlist} / {@code unbanlist} when given.
	 */
	private ShowdownData withMod(java.util.function.Function<String, String> file) {
		Map<String, Map<String, String>> tiers = tsEntries(file.apply("formats-data.ts"));
		Map<String, SpeciesDef> moddedSpecies = new HashMap<>(species);
		tiers.forEach((id, fields) -> {
			SpeciesDef def = species.get(id);
			if (def != null) {
				moddedSpecies.put(id, new SpeciesDef(def.id(), def.name(), def.baseSpeciesId(), def.types(), def.prevo(),
						def.evos(), fields.get("isNonstandard"), fields.get("tier"), fields.get("natDexTier"),
						orElse(fields.get("doublesTier"), fields.get("tier")), def.tags()));
			}
		});
		Map<String, RuleDef> moddedRules = new HashMap<>(rules);
		parseRules(file.apply("rulesets.ts")).forEach((id, change) -> {
			RuleDef def = rules.get(id);
			if (def != null) {
				moddedRules.put(id, new RuleDef(id, def.name(), change.ruleset() != null ? change.ruleset() : def.ruleset(),
						change.banlist() != null ? change.banlist() : def.banlist(),
						change.unbanlist() != null ? change.unbanlist() : def.unbanlist()));
			}
		});
		return new ShowdownData(formats, moddedSpecies, nonstandard(items, file.apply("items.ts")),
				nonstandard(moves, file.apply("moves.ts")), nonstandard(abilities, file.apply("abilities.ts")), moddedRules);
	}

	private static Map<String, EntryDef> nonstandard(Map<String, EntryDef> base, String source) {
		Map<String, EntryDef> result = new HashMap<>(base);
		tsEntries(source).forEach((id, fields) -> {
			EntryDef def = base.get(id);
			if (def != null && fields.containsKey("isNonstandard")) {
				result.put(id, new EntryDef(def.id(), def.name(), fields.get("isNonstandard"), def.ohko(), def.status(),
						def.evasionBoost(), def.accuracyBoost(), def.selfTarget(), def.megaStone(), def.itemUser()));
			}
		});
		return result;
	}

	private static final Pattern TS_ENTRY = Pattern.compile("(?ms)^\\t([a-z0-9]+): \\{\\n(.*?)^\\t\\},?$");
	private static final Pattern TS_FIELD = Pattern.compile("(?m)^\\t\\t([A-Za-z]+): (null|\"([^\"]*)\"|'([^']*)'),?$");

	/** The top-level entries of a mod's {@code .ts} table: id → its plain fields (string or null) at the entry's level. */
	static Map<String, Map<String, String>> tsEntries(String source) {
		Map<String, Map<String, String>> entries = new HashMap<>();
		Matcher entry = TS_ENTRY.matcher(source.replace("\r\n", "\n"));
		while (entry.find()) {
			Map<String, String> fields = new HashMap<>();
			Matcher field = TS_FIELD.matcher(entry.group(2));
			while (field.find()) {
				fields.put(field.group(1), field.group(3) != null ? field.group(3) : field.group(4));
			}
			entries.put(entry.group(1), fields);
		}
		return entries;
	}

	/** This data as the formats of {@code mod} see it (itself for no mod, or a mod not read). */
	public ShowdownData forMod(String mod) {
		return mod == null ? this : modded.getOrDefault(mod, this);
	}

	private static Map<String, EntryDef> entries(String file) {
		Map<String, EntryDef> result = new HashMap<>();
		for (Map.Entry<String, JsonNode> entry : literal(file).properties()) {
			JsonNode node = entry.getValue();
			JsonNode boosts = node.get("boosts");
			int evasion = boosts == null || !boosts.has("evasion") ? 0 : boosts.get("evasion").asInt();
			int accuracy = boosts == null || !boosts.has("accuracy") ? 0 : boosts.get("accuracy").asInt();
			JsonNode ohko = node.get("ohko");
			// megaStone: {"Charizard": "Charizard-Mega-X"} (older data: just "Charizard-Mega-X").
			Map<String, String> megaStone = new HashMap<>();
			JsonNode mega = node.get("megaStone");
			if (mega != null && mega.isObject()) {
				mega.properties().forEach(stone -> megaStone.put(stone.getKey(), stone.getValue().asString()));
			} else if (mega != null && mega.isString()) {
				megaStone.put("", mega.asString());
			}
			result.put(entry.getKey(), new EntryDef(entry.getKey(), text(node, "name"), text(node, "isNonstandard"),
					ohko != null && !(ohko.isBoolean() && !ohko.asBoolean()), text(node, "status"),
					evasion, accuracy, "self".equals(text(node, "target")), Map.copyOf(megaStone),
						strings(node.get("itemUser"))));
		}
		return result;
	}

	private static final Pattern RULE_START = Pattern.compile("(?m)^\\t([a-z0-9]+): \\{");
	private static final Pattern RULE_NAME = Pattern.compile("\\n\\t\\tname: (['\"])(.*?)\\1");
	private static final Pattern STRING = Pattern.compile("'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\"");

	/**
	 * Each top-level rule of {@code rulesets.ts}: its name and its {@code ruleset} / {@code banlist} / {@code unbanlist}
	 * (empty when absent). A mod's file redefines rules without a name ({@code inherit: true}): those are read with a
	 * null name and null for an absent list, see {@link #withMod}.
	 */
	static Map<String, RuleDef> parseRules(String text) {
		String source = text.replace("\r\n", "\n"); // a checkout may have turned the snapshot's line ends into CRLF
		Map<String, RuleDef> rules = new HashMap<>();
		Matcher start = RULE_START.matcher(source);
		List<int[]> blocks = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		while (start.find()) {
			ids.add(start.group(1));
			blocks.add(new int[] { start.start() });
		}
		for (int i = 0; i < blocks.size(); i++) {
			int from = blocks.get(i)[0];
			int to = i + 1 < blocks.size() ? blocks.get(i + 1)[0] : source.length();
			String block = source.substring(from, to);
			Matcher name = RULE_NAME.matcher(block);
			if (name.find()) {
				rules.put(ids.get(i), new RuleDef(ids.get(i), name.group(2), orEmpty(listIn(block, "ruleset")),
						orEmpty(listIn(block, "banlist")), orEmpty(listIn(block, "unbanlist"))));
			} else if (block.contains("\n\t\tinherit: true")) {
				rules.put(ids.get(i), new RuleDef(ids.get(i), null, listIn(block, "ruleset"), listIn(block, "banlist"),
						listIn(block, "unbanlist")));
			}
		}
		return rules;
	}

	private static List<String> orEmpty(List<String> list) {
		return list == null ? List.of() : list;
	}

	/** The strings of a {@code key: [ ... ]} array at the rule's own level (two tabs), or null when there is none. */
	private static List<String> listIn(String block, String key) {
		int at = block.indexOf("\n\t\t" + key + ": [");
		if (at < 0) {
			return null;
		}
		int open = block.indexOf('[', at);
		int close = block.indexOf(']', open);
		List<String> values = new ArrayList<>();
		Matcher string = STRING.matcher(block.substring(open + 1, close));
		while (string.find()) {
			values.add(string.group(1) != null ? string.group(1) : string.group(2));
		}
		return values;
	}

	private static JsonNode literal(String file) {
		if (file == null) {
			throw new IllegalArgumentException("Missing Showdown data file");
		}
		int start = file.indexOf('=');
		int end = file.lastIndexOf(';');
		String body = file.substring(start + 1, end > start ? end : file.length()).trim();
		return LENIENT.readTree(body);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

	private static String orElse(String value, String fallback) {
		return value != null ? value : fallback;
	}

	private static List<String> strings(JsonNode array) {
		List<String> values = new ArrayList<>();
		if (array != null && array.isArray()) {
			array.forEach(value -> values.add(value.asString()));
		}
		return values;
	}

	/** Showdown's id convention: lower case, letters and digits only. */
	public static String toId(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	public FormatDef format(String id) {
		return formats.get(id);
	}

	public SpeciesDef species(String id) {
		return species.get(id);
	}

	public EntryDef item(String id) {
		return items.get(id);
	}

	public EntryDef move(String id) {
		return moves.get(id);
	}

	public EntryDef ability(String id) {
		return abilities.get(id);
	}

	public RuleDef rule(String id) {
		return rules.get(id);
	}
}
