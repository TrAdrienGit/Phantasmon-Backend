package com.mystaria.phantasmon_backend.pokemon;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;
import com.mystaria.phantasmon_backend.player.PlayerService;

import static org.assertj.core.api.Assertions.assertThat;

/** V10 folds Showdown's per-type Hidden Power ids into Cobblemon's single "hiddenpower" (re-runnable script). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@Transactional
class HiddenPowerMigrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	private UUID insert(UUID ownerUuid, int slot, String dataJson) {
		UUID uuid = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO pokemon (uuid, owner_uuid, species, level, nature, ability, is_shiny, box_id, box_slot,
				                     cobblemon_data_version, data)
				VALUES (?, ?, 'landorus', 50, 'timid', 'sheer_force', false, 1, ?, '1.8.1', ?::jsonb)
				""", uuid, ownerUuid, slot, dataJson);
		return uuid;
	}

	private String moves(UUID uuid) {
		return jdbc.queryForObject("SELECT data->>'moves' FROM pokemon WHERE uuid = ?", String.class, uuid);
	}

	@Test
	void variantIdsBecomeTheSingleCobblemonMoveKeepingOrderAndOtherMoves() throws Exception {
		UUID ownerUuid = UUID.randomUUID();
		playerService.recordConnection(ownerUuid, "Bichou");
		entityManager.flush();
		UUID variant = insert(ownerUuid, 1, "{\"moves\":[\"earthpower\",\"hiddenpowerice\",\"psychic\"],\"nickname\":\"X\"}");
		UUID plain = insert(ownerUuid, 2, "{\"moves\":[\"hiddenpower\",\"rockslide\"]}");
		UUID none = insert(ownerUuid, 3, "{\"ivs\":{}}");

		String script = new ClassPathResource("db/migration/V10__pokemon_hidden_power_single_id.sql")
				.getContentAsString(StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
		jdbc.execute(script);
		jdbc.execute(script);

		assertThat(moves(variant)).isEqualTo("[\"earthpower\", \"hiddenpower\", \"psychic\"]");
		assertThat(jdbc.queryForObject("SELECT data->>'nickname' FROM pokemon WHERE uuid = ?", String.class, variant)).isEqualTo("X");
		assertThat(moves(plain)).isEqualTo("[\"hiddenpower\", \"rockslide\"]");
		assertThat(jdbc.queryForObject("SELECT data::text FROM pokemon WHERE uuid = ?", String.class, none)).isEqualTo("{\"ivs\": {}}");
	}
}
