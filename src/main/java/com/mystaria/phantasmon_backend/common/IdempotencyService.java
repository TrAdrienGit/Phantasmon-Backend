package com.mystaria.phantasmon_backend.common;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Anti-duplication wrapper for sensitive POSTs (CAD Partie 2 §12): the first
 * call for a given {@code request_uuid} executes {@code action} and stores
 * its result; every subsequent call with the same {@code request_uuid}
 * replays the stored result without re-executing {@code action}.
 *
 * <p>Race-proof (DEBT-4): the key is reserved first with an atomic
 * {@code INSERT … ON CONFLICT DO NOTHING}, in the same transaction as the
 * action. A simultaneous duplicate waits for the first to commit, then
 * replays its response; if the action fails, the reservation is rolled back
 * with it and the request can be retried.
 */
@Service
public class IdempotencyService {

	private final IdempotencyKeyRepository repository;
	private final ObjectMapper objectMapper;

	public IdempotencyService(IdempotencyKeyRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public <T> T executeIdempotent(UUID requestUuid, UUID playerUuid, String endpoint, Supplier<T> action, Class<T> responseType) {
		if (repository.claim(requestUuid, playerUuid, endpoint) == 1) {
			T result = action.get();
			IdempotencyKey reserved = repository.findById(requestUuid).orElseThrow();
			reserved.recordResponse(objectMapper.convertValue(result, Map.class));
			return result;
		}
		IdempotencyKey existing = repository.findById(requestUuid).orElseThrow();
		// Only the same player replaying the same route gets the stored response back (SEC-7).
		if (!existing.getPlayerUuid().equals(playerUuid) || !existing.getEndpoint().equals(endpoint)) {
			throw new ApiException(HttpStatus.CONFLICT, "ERROR_IDEMPOTENCY_KEY_REUSED", Map.of("request_uuid", requestUuid));
		}
		return objectMapper.convertValue(existing.getResponseSnapshot(), responseType);
	}
}
