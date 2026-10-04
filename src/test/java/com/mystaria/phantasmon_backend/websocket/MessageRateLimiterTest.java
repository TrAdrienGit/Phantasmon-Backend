package com.mystaria.phantasmon_backend.websocket;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRateLimiterTest {

	private static final long SECOND = 1_000_000_000L;

	@Test
	void aBurstUpToTheCapacityIsAllowedThenRefused() {
		AtomicLong now = new AtomicLong();
		MessageRateLimiter limiter = new MessageRateLimiter(10, 3, now::get);

		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();
	}

	@Test
	void tokensComeBackOverTime() {
		AtomicLong now = new AtomicLong();
		MessageRateLimiter limiter = new MessageRateLimiter(10, 3, now::get);
		for (int i = 0; i < 3; i++) {
			limiter.tryAcquire();
		}

		now.addAndGet(SECOND / 10);
		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();

		now.addAndGet(10 * SECOND);
		assertThat(limiter.tryAcquire()).as("never more than the capacity, however long the pause").isTrue();
		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();
	}

	@Test
	void theFirstRefusalOfAStreakIsReportedOnce() {
		AtomicLong now = new AtomicLong();
		MessageRateLimiter limiter = new MessageRateLimiter(10, 1, now::get);
		limiter.tryAcquire();

		assertThat(limiter.tryAcquire()).isFalse();
		assertThat(limiter.shouldReportRefusal()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();
		assertThat(limiter.shouldReportRefusal()).as("one error per streak, not one per dropped message").isFalse();

		now.addAndGet(SECOND);
		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();
		assertThat(limiter.shouldReportRefusal()).isTrue();
	}
}
