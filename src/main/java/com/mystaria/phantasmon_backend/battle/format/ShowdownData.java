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
 */
public final class ShowdownData {

	public static final List<String> FILES = List.of(
			"formats.js", "formats-data.js", "pokedex.js", "items.js", "moves.js", "abilities.js", "rulesets.ts");

	private static final JsonMapper LENIENT = JsonMapper.builder()
			.enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
			.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
			.enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
			.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
			.build();

	/** A format of {@code formats.js}. */
	public record FormatDef(String name, String mod, List<String> ruleset, List<String> banlist, List<String> unbanlist) {
	}

	/** A Pokémon species / forme of {@code pokedex.js} plus its tiers from {@code formats-data.js}. */
	public record SpeciesDef(String id, String name, String baseSpeciesId, List<String> types, String prevo, List<String> evos,
			String isNonstandard, String tier, String natDexTier) {
	}

	/** An item, ability or move: its name, its non-standard status, and (moves) the flags the clauses look at. */
	public record EntryDef(String id, String name, String isNonstandard, boolean ohko, String status, int evasionBoost,
			int accuracyBoost, boolean selfTarget, Map<String, String> megaStone) {

		/** Items: the Mega forme this stone gives {@code speciesName} (Showdown name), or null. */
		public String megaFormeFor(String speciesName) {
			if (megaStone.isEmpty()) {
				return null;
			}
			String forme = megaStone.get(speciesName);
			return forme != null ? forme : megaStone.size() == 1 && megaStone.containsKey("") ? megaStone.get("") : null;
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
				formats.put(toId(name), new FormatDef(name, text(node, "mod"), strings(node.get("ruleset")),
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
					tier == null ? null : text(tier, "tier"), tier == null ? null : text(tier, "natDexTier")));
		}

		return new ShowdownData(formats, species, entries(files.get("items.js")), entries(files.get("moves.js")),
				entries(files.get("abilities.js")), parseRules(files.get("rulesets.ts")));
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
					evasion, accuracy, "self".equals(text(node, "target")), Map.copyOf(megaStone)));
		}
		return result;
	}

	private static final Pattern RULE_START = Pattern.compile("(?m)^\\t([a-z0-9]+): \\{");
	private static final Pattern RULE_NAME = Pattern.compile("\\n\\t\\tname: (['\"])(.*?)\\1");
	private static final Pattern STRING = Pattern.compile("'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\"");

	/** Each top-level rule of {@code rulesets.ts}: its name and its {@code ruleset} / {@code banlist} / {@code unbanlist}. */
	static Map<String, RuleDef> parseRules(String source) {
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
			if (!name.find()) {
				continue;
			}
			rules.put(ids.get(i), new RuleDef(ids.get(i), name.group(2), listIn(block, "ruleset"), listIn(block, "banlist"),
					listIn(block, "unbanlist")));
		}
		return rules;
	}

	/** The strings of a {@code key: [ ... ]} array at the rule's own level (two tabs), if any. */
	private static List<String> listIn(String block, String key) {
		int at = block.indexOf("\n\t\t" + key + ": [");
		if (at < 0) {
			return List.of();
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
