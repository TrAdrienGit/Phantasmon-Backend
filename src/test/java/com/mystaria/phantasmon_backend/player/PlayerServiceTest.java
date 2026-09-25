package com.mystaria.phantasmon_backend.player;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PlayerServiceTest {

	@Autowired
	private PlayerService playerService;

	@Autowired
	private PlayerRepository playerRepository;

	@Test
	void recordConnectionCreatesPlayerOnFirstConnection() {
		UUID uuid = UUID.randomUUID();

		Player player = playerService.recordConnection(uuid, "Bichou");

		assertThat(player.getUuid()).isEqualTo(uuid);
		assertThat(player.getLastUsername()).isEqualTo("Bichou");
		assertThat(player.getCreatedAt()).isNotNull();
		assertThat(player.getLastSeenAt()).isNotNull();
		assertThat(playerRepository.findById(uuid)).isPresent();
	}

	@Test
	void recordConnectionUpdatesUsernameAndLastSeenOnSubsequentConnection() {
		UUID uuid = UUID.randomUUID();
		Player first = playerService.recordConnection(uuid, "OldName");
		Instant createdAt = first.getCreatedAt();

		Player second = playerService.recordConnection(uuid, "NewName");

		assertThat(second.getUuid()).isEqualTo(uuid);
		assertThat(second.getLastUsername()).isEqualTo("NewName");
		assertThat(second.getCreatedAt()).isEqualTo(createdAt);
		assertThat(playerRepository.findAll()).hasSize(1);
	}
}
