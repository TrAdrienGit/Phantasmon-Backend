package com.mystaria.phantasmon_backend.pokemon;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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

/**
 * V9 renames the camelCase keys of {@code pokemon.data} (DEBT-1). The test database is already at the latest
 * version, so the script — written to be re-runnable — is played again on rows inserted in the old format.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@Transactional
class SnakeCaseDataKeysMigrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlayerService playerService;

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	private void runV9() throws Exception {
		String script = new ClassPathResource("db/migration/V9__pokemon_data_snake_case_keys.sql")
				.getContentAsString(StandardCharsets.UTF_8);
		for (String statement : script.replaceAll("(?m)^--.*$", "").split(";")) {
			if (!statement.isBlank()) {
				jdbc.execute(statement);
			}
		}
	}

	@Test
	void oldCamelCaseKeysAreRenamedAndEverythingElseIsKept() throws Exception {
		UUID ownerUuid = UUID.randomUUID();
		playerService.recordConnection(ownerUuid, "Bichou");
		entityManager.flush(); // the raw JDBC inserts below need the player row for their foreign key
		UUID pokemonUuid = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO pokemon (uuid, owner_uuid, species, level, nature, ability, is_shiny, box_id, box_slot,
				                     cobblemon_data_version, data)
				VALUES (?, ?, 'pikachu', 50, 'timid', 'static', false, 1, 1, '1.8.1',
				        '{"heldItem":"choice_scarf","teraType":"water","friendship":70,"nickname":"Bichou","ivs":{"hp":31}}'::jsonb)
				""", pokemonUuid, ownerUuid);
		UUID untouchedUuid = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO pokemon (uuid, owner_uuid, species, level, nature, ability, is_shiny, box_id, box_slot,
				                     cobblemon_data_version, data)
				VALUES (?, ?, 'eevee', 5, 'hardy', 'run_away', false, 1, 2, '1.8.1', '{"ivs":{}}'::jsonb)
				""", untouchedUuid, ownerUuid);

		runV9();

		Map<String, Object> keys = jdbc.queryForMap("""
				SELECT jsonb_exists(data, 'heldItem') AS old_item, jsonb_exists(data, 'teraType') AS old_tera,
				       data->>'held_item' AS held_item, data->>'tera_type' AS tera_type,
				       data->>'friendship' AS friendship, data->>'nickname' AS nickname, data->'ivs'->>'hp' AS hp
				FROM pokemon WHERE uuid = ?
				""", pokemonUuid);
		assertThat(keys.get("old_item")).isEqualTo(false);
		assertThat(keys.get("old_tera")).isEqualTo(false);
		assertThat(keys.get("held_item")).isEqualTo("choice_scarf");
		assertThat(keys.get("tera_type")).isEqualTo("water");
		assertThat(keys.get("friendship")).isEqualTo("70");
		assertThat(keys.get("nickname")).isEqualTo("Bichou");
		assertThat(keys.get("hp")).isEqualTo("31");
		assertThat(jdbc.queryForObject("SELECT data::text FROM pokemon WHERE uuid = ?", String.class, untouchedUuid))
				.isEqualTo("{\"ivs\": {}}");
	}

	@Test
	void storedIdempotentResponsesAreRenamedToo() throws Exception {
		UUID requestUuid = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO idempotency_keys (request_uuid, player_uuid, endpoint, response_snapshot, created_at)
				VALUES (?, ?, 'POST /pokemon', '{"uuid":"x","data":{"heldItem":"leftovers","teraType":"fire"}}'::jsonb, now())
				""", requestUuid, UUID.randomUUID());

		runV9();

		Map<String, Object> keys = jdbc.queryForMap("""
				SELECT response_snapshot->'data'->>'held_item' AS held_item, response_snapshot->'data'->>'tera_type' AS tera_type,
				       jsonb_exists(response_snapshot->'data', 'heldItem') AS old_item
				FROM idempotency_keys WHERE request_uuid = ?
				""", requestUuid);
		assertThat(keys.get("held_item")).isEqualTo("leftovers");
		assertThat(keys.get("tera_type")).isEqualTo("fire");
		assertThat(keys.get("old_item")).isEqualTo(false);
	}
}
