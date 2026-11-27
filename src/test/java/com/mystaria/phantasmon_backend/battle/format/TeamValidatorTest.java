package com.mystaria.phantasmon_backend.battle.format;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TODO-24 — formats and team checks, on the Showdown snapshot shipped with the backend. */
class TeamValidatorTest {

	private static BattleFormats formats;
	private static TeamValidator validator;

	@BeforeAll
	static void load() {
		ShowdownData data = ShowdownDataSource.bundledData();
		formats = new BattleFormats(data);
		validator = new TeamValidator(data);
	}

	private static TeamValidator.Member mon(String species, String item, String... moves) {
		return new TeamValidator.Member(species, null, "pressure", item, List.of(moves), null);
	}

	private static List<List<TeamValidator.Issue>> check(String format, TeamValidator.Member... team) {
		return validator.validate(formats.get(format), List.of(team));
	}

	private static TeamValidator.Member preTera(String species, String item) {
		return new TeamValidator.Member(species, null, "pressure", item, List.of("tackle"), null, true);
	}

	@Test
	void onePokemonTerastallizedFromTheStartPerTeamWhateverTheFormat() {
		var issues = check(BattleFormats.FREE, preTera("garchomp", null), preTera("dragonite", null));
		assertThat(issues.get(0)).isEmpty();
		assertThat(issues.get(1)).contains(new TeamValidator.Issue("PRE_TERA_TWICE", ""));
	}

	@Test
	void terastallizedFromTheStartBypassesTheTerastalClauseButNotItsItems() {
		assertThat(check("gen9nationaldex", preTera("garchomp", null)).get(0)).isEmpty();
		var items = check(BattleFormats.FREE, preTera("garchomp", "garchompite"), mon("pikachu", null));
		assertThat(items.get(0)).contains(new TeamValidator.Issue("PRE_TERA_ITEM", "Garchompite"));
		assertThat(check("gen9ou", preTera("garchomp", "choicescarf")).get(0)).isEmpty();
	}

	@Test
	void everyOfferedFormatResolves() {
		for (String id : BattleFormats.OFFERED) {
			assertThat(formats.get(id)).as(id).isNotNull();
		}
		assertThat(formats.offered()).hasSize(29);
		assertThat(formats.get("gen9nationaldex").name()).isEqualTo("[Gen 9] National Dex");
	}

	@Test
	void theFreeFormatChecksNothing() {
		var issues = check(BattleFormats.FREE, mon("mewtwo", "mewtwonitex", "fissure"), mon("mewtwo", null));
		assertThat(issues).allMatch(List::isEmpty);
		assertThat(formats.get(BattleFormats.FREE).adjustLevel()).isZero();
	}

	@Test
	void nationalDexAllowsPastPokemonAndMegaStonesButBansUbers() {
		var issues = check("gen9nationaldex", mon("rattata", null, "tackle"), mon("garchomp", "garchompite", "earthquake"),
				mon("mewtwo", null, "psychic"));
		assertThat(issues.get(0)).as("Past species is fine in National Dex").isEmpty();
		assertThat(issues.get(1)).as("Mega Stones are National Dex items").isEmpty();
		assertThat(issues.get(2)).contains(new TeamValidator.Issue("BANNED", "Mewtwo"));
		assertThat(formats.get("gen9nationaldex").battleRules()).contains("Sleep Clause Mod", "Terastal Clause");
	}

	@Test
	void gen9RefusesWhatIsNotInScarletViolet() {
		var issues = check("gen9ou", mon("rattata", null, "tackle"), mon("garchomp", "garchompite", "earthquake"));
		assertThat(issues.get(0)).contains(new TeamValidator.Issue("NONSTANDARD", "Rattata"));
		assertThat(issues.get(1)).contains(new TeamValidator.Issue("NONSTANDARD", "Garchompite"));
	}

	@Test
	void clausesFlagTheOffendingMembers() {
		var issues = check("gen9nationaldex", mon("garchomp", null, "earthquake"), mon("garchomp", null, "fissure"),
				mon("blissey", "brightpowder", "doubleteam"));
		assertThat(issues.get(0)).isEmpty();
		assertThat(issues.get(1)).contains(new TeamValidator.Issue("CLAUSE", "Species Clause"),
				new TeamValidator.Issue("CLAUSE", "OHKO Clause"));
		assertThat(issues.get(2)).contains(new TeamValidator.Issue("CLAUSE", "Evasion Items Clause"),
				new TeamValidator.Issue("CLAUSE", "Evasion Moves Clause"));
	}

	@Test
	void ouBansSleepMovesAndLowerTiersBanHigherOnes() {
		assertThat(check("gen9ou", mon("amoonguss", null, "spore")).get(0))
				.contains(new TeamValidator.Issue("CLAUSE", "Sleep Moves Clause"));
		var uu = check("gen9uu", mon("greattusk", null, "earthquake"));
		assertThat(uu.get(0)).as("an OU Pokémon is banned from UU").contains(new TeamValidator.Issue("BANNED", "Great Tusk"));
	}

	@Test
	void littleCupAndMonotypeAndTeamSize() {
		var lc = check("gen9nationaldexlc", mon("gible", null, "tackle"), mon("garchomp", null, "tackle"));
		assertThat(lc.get(0)).isEmpty();
		assertThat(lc.get(1)).contains(new TeamValidator.Issue("LITTLE_CUP", "Garchomp"));
		assertThat(formats.get("gen9nationaldexlc").adjustLevel()).isEqualTo(5);

		var mono = check("gen9nationaldexmonotype", mon("garchomp", null, "tackle"), mon("hippowdon", null, "tackle"),
				mon("blissey", null, "tackle"));
		assertThat(mono.get(0)).isEmpty();
		assertThat(mono.get(2)).contains(new TeamValidator.Issue("SAME_TYPE", "Ground"));

		BattleFormats.Format oneVsOne = formats.get("gen9nationaldex1v1");
		assertThat(oneVsOne.pickedTeamSize()).isEqualTo(1);
		var big = check("gen9nationaldex1v1", mon("garchomp", null, "tackle"), mon("blissey", null, "tackle"),
				mon("hippowdon", null, "tackle"), mon("lucario", null, "tackle"));
		assertThat(big.get(3)).contains(new TeamValidator.Issue("TEAM_SIZE", "3"));
		assertThat(formats.get("gen9ou").adjustLevel()).isEqualTo(100);
	}

	@Test
	void aMegaStoneIsBannedWhenItsMegaFormeIs() {
		// Showdown bans the stone through the Mega forme's tier: Gengar-Mega is ND AG, Lucario-Mega ND Uber.
		var issues = check("gen9nationaldex", mon("gengar", "gengarite", "shadowball"), mon("lucario", "lucarionite", "aurasphere"),
				mon("garchomp", "garchompite", "earthquake"));
		assertThat(issues.get(0)).contains(new TeamValidator.Issue("BANNED", "Gengarite"));
		assertThat(issues.get(1)).contains(new TeamValidator.Issue("BANNED", "Lucarionite"));
		assertThat(issues.get(2)).as("Garchomp-Mega is ND OU").isEmpty();
		assertThat(check("gen9nationaldexubers", mon("lucario", "lucarionite", "aurasphere")).get(0)).isEmpty();
	}

	private static TeamValidator.Member form(String species, String form, String item) {
		return new TeamValidator.Member(species, form, "pressure", item, List.of("protect"), null);
	}

	@Test
	void newMegaStonesAreLegalInChampionsFormatsAndFreeButNotInSmogonFormats() {
		var champions = validator.validate(formats.get("gen9championsou"),
				List.of(mon("clefable", "clefablite"), form("meowstic", "f", "meowsticite")));
		assertThat(champions).allMatch(List::isEmpty);
		assertThat(check(BattleFormats.FREE, mon("clefable", "clefablite"), form("meowstic", "f", "meowsticite")))
				.allMatch(List::isEmpty);
		for (String format : List.of("gen9ou", "gen9nationaldex", "gen9anythinggoes")) {
			var issues = check(format, mon("clefable", "clefablite"), form("meowstic", "f", "meowsticite"));
			assertThat(issues.get(0)).as(format).contains(new TeamValidator.Issue("NONSTANDARD", "Clefablite"));
			assertThat(issues.get(1)).as(format).contains(new TeamValidator.Issue("NONSTANDARD", "Meowsticite"));
		}
	}

	@Test
	void aMegaStoneOnlyGoesToItsOwnSpecies() {
		var champions = formats.get("gen9championsou");
		assertThat(champions).isNotNull();
		assertThat(validator.validate(champions, List.of(mon("pikachu", "clefablite"))).get(0))
				.contains(new TeamValidator.Issue("BANNED", "Clefablite"));
		assertThat(check("gen9nationaldex", mon("pikachu", "garchompite")).get(0))
				.contains(new TeamValidator.Issue("BANNED", "Garchompite"));
		assertThat(check("gen9nationaldex", mon("garchomp", "garchompite")).get(0)).isEmpty();
	}

	@Test
	void championsFormatsAreOfferedWithChampionsOwnRules() {
		BattleFormats.Format ou = formats.get("gen9championsou");
		assertThat(ou).isNotNull();
		assertThat(ou.champions()).isTrue();
		assertThat(ou.adjustLevel()).isEqualTo(50); // Champions' Standard AG: Adjust Level = 50
		assertThat(ou.battleRules()).contains("Terastal Clause");
		assertThat(formats.get("gen9championsuu")).isNotNull();
		assertThat(formats.get("gen9championsnationaldex")).isNotNull();
		assertThat(formats.offered()).extracting(BattleFormats.Format::id).contains("gen9championsou");
	}

	@Test
	void championsFormatsUseChampionsListsOfPokemonAndItems() {
		// Bulbasaur and the Ability Shield are not in Champions, but are in Gen 9.
		assertThat(check("gen9championsou", mon("bulbasaur", null)).get(0))
				.contains(new TeamValidator.Issue("NONSTANDARD", "Bulbasaur"));
		assertThat(check("gen9championsou", mon("garchomp", "abilityshield")).get(0))
				.contains(new TeamValidator.Issue("NONSTANDARD", "Ability Shield"));
		assertThat(check("gen9ou", mon("garchomp", "abilityshield")).get(0)).isEmpty();
	}

	@Test
	void championsFormatsHaveTheItemClauseAndNoTerastallization() {
		var issues = check("gen9championsou", mon("garchomp", "leftovers"), mon("dragonite", "leftovers"));
		assertThat(issues.get(0)).isEmpty();
		assertThat(issues.get(1)).contains(new TeamValidator.Issue("CLAUSE", "Item Clause"));
		assertThat(check("gen9ou", mon("garchomp", "leftovers"), mon("dragonite", "leftovers"))).allMatch(List::isEmpty);
		assertThat(check("gen9championsou", preTera("garchomp", null)).get(0))
				.contains(new TeamValidator.Issue("CLAUSE", "Terastal Clause"));
	}

	@Test
	void doublesFormatsAreOfferedAsDoubles() {
		for (String id : List.of("gen9doublesou", "gen9doublesubers", "gen9doublesuu", "gen9nationaldexdoubles",
				"gen9championsdoubles")) {
			assertThat(formats.get(id).doubles()).as(id).isTrue();
			assertThat(formats.get(id).activeCount()).isEqualTo(2);
		}
		assertThat(formats.get("gen9ou").doubles()).isFalse();
		assertThat(formats.get(BattleFormats.FREE).gameType()).isEqualTo(BattleFormats.SINGLES);
		assertThat(formats.get("gen9championsdoubles").champions()).isTrue();
	}

	@Test
	void vgcPicksFourOfSixAtLevelFiftyWithoutRestrictedOrMythical() {
		BattleFormats.Format vgc = formats.get("gen9championsvgc2026regmc");
		assertThat(vgc.doubles()).isTrue();
		assertThat(vgc.pickedTeamSize()).isEqualTo(4);
		assertThat(vgc.adjustLevel()).isEqualTo(50);
		assertThat(formats.get("gen9championsdoubles").pickedTeamSize()).isZero();
		assertThat(formats.get("gen9doublesou").pickedTeamSize()).isZero();
		assertThat(check("gen9championsvgc2026regmc", mon("mewtwo", null, "protect")).get(0))
				.contains(new TeamValidator.Issue("BANNED", "Mewtwo"));
		assertThat(check("gen9championsvgc2026regmc", mon("mew", null, "protect")).get(0))
				.contains(new TeamValidator.Issue("BANNED", "Mew"));
	}

	@Test
	void doublesFormatsBanByDoublesTiers() {
		// Sneasler: Uber in singles, DOU in doubles.
		assertThat(check("gen9doublesou", mon("sneasler", null, "protect")).get(0)).isEmpty();
		assertThat(check("gen9doublesuu", mon("sneasler", null, "protect")).get(0))
				.contains(new TeamValidator.Issue("BANNED", "Sneasler"));
		assertThat(check("gen9doublesou", mon("mewtwo", null, "protect")).get(0))
				.contains(new TeamValidator.Issue("BANNED", "Mewtwo"));
		assertThat(check("gen9doublesubers", mon("mewtwo", null, "protect")).get(0)).isEmpty();
	}

	@Test
	void gravitySleepClauseFlagsGravityWithAnInaccurateSleepMove() {
		var issues = check("gen9doublesou", mon("bronzong", null, "gravity"), mon("amoonguss", null, "sleeppowder"),
				mon("garchomp", null, "protect"));
		assertThat(issues.get(0)).contains(new TeamValidator.Issue("CLAUSE", "Gravity Sleep Clause"));
		assertThat(issues.get(1)).contains(new TeamValidator.Issue("CLAUSE", "Gravity Sleep Clause"));
		assertThat(issues.get(2)).isEmpty();
		assertThat(check("gen9doublesubers", mon("bronzong", null, "gravity"), mon("amoonguss", null, "sleeppowder")))
				.as("Doubles Ubers lifts the clause").allMatch(List::isEmpty);
	}
}
