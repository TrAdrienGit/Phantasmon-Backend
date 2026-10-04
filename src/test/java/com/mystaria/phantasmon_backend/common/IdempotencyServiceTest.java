package com.mystaria.phantasmon_backend.common;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.mystaria.phantasmon_backend.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "phantasmon.logging.enabled=false")
@Transactional
class IdempotencyServiceTest {

	private record Payload(String value) {
	}

	@Autowired
	private IdempotencyService idempotencyService;

	@Test
	void executesTheActionOnlyOnceForTheSameRequestUuid() {
		UUID requestUuid = UUID.randomUUID();
		UUID playerUuid = UUID.randomUUID();
		AtomicInteger callCount = new AtomicInteger();

		Payload first = idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /pokemon",
				() -> {
					callCount.incrementAndGet();
					return new Payload("created-" + callCount.get());
				}, Payload.class);

		Payload second = idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /pokemon",
				() -> {
					callCount.incrementAndGet();
					return new Payload("created-" + callCount.get());
				}, Payload.class);

		assertThat(callCount.get()).isEqualTo(1);
		assertThat(second).isEqualTo(first);
		assertThat(second.value()).isEqualTo("created-1");
	}

	@Test
	void differentRequestUuidsExecuteIndependently() {
		UUID playerUuid = UUID.randomUUID();
		AtomicInteger callCount = new AtomicInteger();

		Payload first = idempotencyService.executeIdempotent(UUID.randomUUID(), playerUuid, "POST /pokemon",
				() -> new Payload("created-" + callCount.incrementAndGet()), Payload.class);
		Payload second = idempotencyService.executeIdempotent(UUID.randomUUID(), playerUuid, "POST /pokemon",
				() -> new Payload("created-" + callCount.incrementAndGet()), Payload.class);

		assertThat(callCount.get()).isEqualTo(2);
		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void anotherPlayersRequestUuidNeverReturnsTheirResponse() {
		// SEC-7: the stored response used to be returned to anyone presenting the same request_uuid.
		UUID requestUuid = UUID.randomUUID();
		idempotencyService.executeIdempotent(requestUuid, UUID.randomUUID(), "POST /pokemon",
				() -> new Payload("alice's secret"), Payload.class);

		assertThatThrownBy(() -> idempotencyService.executeIdempotent(requestUuid, UUID.randomUUID(), "POST /pokemon",
				() -> new Payload("mallory"), Payload.class))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("ERROR_IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void theSameRequestUuidOnAnotherRouteIsRefused() {
		UUID requestUuid = UUID.randomUUID();
		UUID playerUuid = UUID.randomUUID();
		idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /pokemon",
				() -> new Payload("pokemon"), Payload.class);

		assertThatThrownBy(() -> idempotencyService.executeIdempotent(requestUuid, playerUuid, "POST /trades",
				() -> new Payload("trade"), Payload.class))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getErrorCode()).isEqualTo("ERROR_IDEMPOTENCY_KEY_REUSED"));
	}
}
