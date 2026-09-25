package com.mystaria.phantasmon_backend.presence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceServiceTest {

	private static final Duration TTL = Duration.ofSeconds(30);

	private static Clock fixedClock(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}

	@Test
	void joinRecordsPresenceWithFingerprintAndDimension() {
		PresenceService service = new PresenceService(fixedClock(Instant.now()), TTL);
		UUID player = UUID.randomUUID();

		service.join(player, "fp-1", "minecraft:overworld");

		Optional<PlayerPresence> presence = service.find(player);
		assertThat(presence).isPresent();
		assertThat(presence.get().serverFingerprint()).isEqualTo("fp-1");
		assertThat(presence.get().dimension()).isEqualTo("minecraft:overworld");
	}

	@Test
	void leaveRemovesPresence() {
		PresenceService service = new PresenceService(fixedClock(Instant.now()), TTL);
		UUID player = UUID.randomUUID();
		service.join(player, "fp-1", "minecraft:overworld");

		service.leave(player);

		assertThat(service.find(player)).isEmpty();
	}

	@Test
	void groupMembersAreOthersSharingFingerprintAndDimensionOnly() {
		PresenceService service = new PresenceService(fixedClock(Instant.now()), TTL);
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();
		UUID carol = UUID.randomUUID();

		service.join(alice, "fp-1", "minecraft:overworld");
		service.join(bob, "fp-1", "minecraft:overworld");
		service.join(carol, "fp-2", "minecraft:overworld");

		assertThat(service.groupMembers(alice)).containsExactly(bob);
		assertThat(service.groupMembers(carol)).isEmpty();
	}

	@Test
	void heartbeatUpdatesLastHeartbeatAt() {
		Instant start = Instant.now();
		java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(start);
		Clock movableClock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		PresenceService service = new PresenceService(movableClock, TTL);
		UUID player = UUID.randomUUID();
		service.join(player, "fp-1", "minecraft:overworld");
		Instant firstHeartbeat = service.find(player).orElseThrow().lastHeartbeatAt();

		now.set(start.plusSeconds(5));
		service.heartbeat(player);

		assertThat(service.find(player).orElseThrow().lastHeartbeatAt()).isAfter(firstHeartbeat);
	}

	@Test
	void cleanupExpiredRemovesOnlyPresencesPastTtl() {
		Instant start = Instant.now();
		java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(start);
		Clock movableClock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		PresenceService service = new PresenceService(movableClock, TTL);
		UUID stale = UUID.randomUUID();
		UUID fresh = UUID.randomUUID();
		service.join(stale, "fp-1", "minecraft:overworld");
		service.join(fresh, "fp-1", "minecraft:overworld");

		now.set(start.plus(TTL).plusSeconds(1));
		service.heartbeat(fresh);

		assertThat(service.cleanupExpired()).containsExactly(stale);
		assertThat(service.find(stale)).isEmpty();
		assertThat(service.find(fresh)).isPresent();
	}

	@Test
	void updatePositionChangesDimensionAndDoesNothingForUnknownPlayer() {
		PresenceService service = new PresenceService(fixedClock(Instant.now()), TTL);
		UUID player = UUID.randomUUID();
		service.join(player, "fp-1", "minecraft:overworld");

		service.updatePosition(player, 1.0, 2.0, 3.0, "minecraft:the_nether");

		assertThat(service.find(player).orElseThrow().dimension()).isEqualTo("minecraft:the_nether");
		assertThat(service.find(player).orElseThrow().position()).isEqualTo(new Position(1.0, 2.0, 3.0));

		// no exception for an unknown player
		service.updatePosition(UUID.randomUUID(), 0, 0, 0, "minecraft:overworld");
	}
}
