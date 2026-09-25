package com.mystaria.phantasmon_backend.pokemon;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@Transactional
class PokemonRepositoryTest {

	@Autowired
	private PokemonRepository pokemonRepository;

	@Autowired
	private PlayerService playerService;

	@Test
	void jsonbDataRoundTripsThroughRealPostgres() {
		UUID ownerUuid = UUID.randomUUID();
		playerService.recordConnection(ownerUuid, "Bichou");

		Map<String, Object> data = Map.of(
				"nickname", "Sparky",
				"ivs", Map.of("hp", 31, "atk", 31, "def", 31, "spa", 31, "spd", 31, "spe", 31),
				"evs", Map.of("hp", 0, "atk", 252, "def", 0, "spa", 0, "spd", 4, "spe", 252),
				"moves", List.of("thunderbolt", "quick-attack"));

		Pokemon pokemon = new Pokemon(UUID.randomUUID(), ownerUuid, "pikachu", null, (short) 50,
				"timid", "static", true, (short) 1, (short) 1, null, "1.8.1", data);

		pokemonRepository.saveAndFlush(pokemon);
		pokemonRepository.flush();

		Pokemon reloaded = pokemonRepository.findById(pokemon.getUuid()).orElseThrow();

		assertThat(reloaded.getData().get("nickname")).isEqualTo("Sparky");
		@SuppressWarnings("unchecked")
		Map<String, Object> ivs = (Map<String, Object>) reloaded.getData().get("ivs");
		assertThat(ivs.get("hp")).isEqualTo(31);
		assertThat(reloaded.getCreatedAt()).isNotNull();
		assertThat(reloaded.getUpdatedAt()).isNotNull();
	}
}
