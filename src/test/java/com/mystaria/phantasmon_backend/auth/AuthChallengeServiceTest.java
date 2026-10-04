package com.mystaria.phantasmon_backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthChallengeServiceTest {

	private static final Duration TTL = Duration.ofSeconds(60);

	private static Clock clockOf(AtomicReference<Instant> now) {
		return new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
	}

	@Test
	void anIssuedChallengeIsConsumedOnce() {
		AuthChallengeService service = new AuthChallengeService(Clock.systemUTC(), TTL);
		String challenge = service.issue();

		assertThat(service.consume(challenge)).isTrue();
		assertThat(service.consume(challenge)).isFalse();
	}

	@Test
	void unknownOrNullChallengesAreRefused() {
		AuthChallengeService service = new AuthChallengeService(Clock.systemUTC(), TTL);

		assertThat(service.consume("abc123")).isFalse();
		assertThat(service.consume(null)).isFalse();
	}

	@Test
	void anExpiredChallengeIsRefused() {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T12:00:00Z"));
		AuthChallengeService service = new AuthChallengeService(clockOf(now), TTL);
		String challenge = service.issue();

		now.set(now.get().plus(TTL).plusSeconds(1));

		assertThat(service.consume(challenge)).isFalse();
	}

	@Test
	void expiredChallengesAreForgotten() {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T12:00:00Z"));
		AuthChallengeService service = new AuthChallengeService(clockOf(now), TTL);
		service.issue();
		service.issue();

		now.set(now.get().plus(TTL).plusSeconds(1));
		service.issue();

		assertThat(service.outstanding()).isEqualTo(1);
	}
}
