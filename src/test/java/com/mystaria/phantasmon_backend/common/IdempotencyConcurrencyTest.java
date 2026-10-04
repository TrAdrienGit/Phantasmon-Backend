package com.mystaria.phantasmon_backend.common;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two identical requests arriving at the same time (DEBT-4): the action must still run once. Not
 * {@code @Transactional} on purpose — each call needs its own real transaction, as in production.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
class IdempotencyConcurrencyTest {

	private record Payload(String value) {
	}

	@Autowired
	private IdempotencyService idempotencyService;

	@Test
	void simultaneousDuplicatesExecuteTheActionOnlyOnce() throws Exception {
		UUID requestUuid = UUID.randomUUID();
		UUID playerUuid = UUID.randomUUID();
		AtomicInteger executions = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);

		Runnable call = () -> {
			try {
				start.await();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		};
		CompletableFuture<Payload> first = CompletableFuture.supplyAsync(() -> {
			call.run();
			return idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /pokemon", () -> {
				sleep(500);
				return new Payload("created-" + executions.incrementAndGet());
			}, Payload.class);
		});
		CompletableFuture<Payload> second = CompletableFuture.supplyAsync(() -> {
			call.run();
			return idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /pokemon", () -> {
				sleep(500);
				return new Payload("created-" + executions.incrementAndGet());
			}, Payload.class);
		});
		start.countDown();

		Payload a = first.get(10, TimeUnit.SECONDS);
		Payload b = second.get(10, TimeUnit.SECONDS);
		assertThat(executions.get()).isEqualTo(1);
		assertThat(a).isEqualTo(b);
	}

	@Test
	void aFailedActionReleasesTheKeySoTheRequestCanBeRetried() {
		UUID requestUuid = UUID.randomUUID();
		UUID playerUuid = UUID.randomUUID();

		try {
			idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /pokemon", () -> {
				throw new IllegalStateException("boom");
			}, Payload.class);
		} catch (IllegalStateException expected) {
			// the key reservation is rolled back with the failed action
		}

		Payload retried = idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /pokemon",
				() -> new Payload("second try"), Payload.class);
		assertThat(retried.value()).isEqualTo("second try");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
