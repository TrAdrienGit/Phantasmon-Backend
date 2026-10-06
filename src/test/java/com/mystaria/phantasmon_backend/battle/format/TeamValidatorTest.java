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

	@Test
	void everyOfferedFormatResolves() {
		for (String id : BattleFormats.OFFERED) {
			assertThat(formats.get(id)).as(id).isNotNull();
		}
		assertThat(formats.offered()).hasSize(20);
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
}

