package com.mystaria.phantasmon_backend.common;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Anti-duplication wrapper for sensitive POSTs (CAD Partie 2 §12): the first
 * call for a given {@code request_uuid} executes {@code action} and stores
 * its result; every subsequent call with the same {@code request_uuid}
 * replays the stored result without re-executing {@code action}.
 *
 * <p>Not race-proof under true concurrent duplicate requests (a check-then-save,
 * not an atomic upsert) — acceptable for V1 since the real-world case is a
 * client retrying sequentially after a timeout, not parallel duplicate calls.
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
		return repository.findById(requestUuid)
				.map(existing -> objectMapper.convertValue(existing.getResponseSnapshot(), responseType))
				.orElseGet(() -> {
					T result = action.get();
					Map<String, Object> snapshot = objectMapper.convertValue(result, Map.class);
					repository.save(new IdempotencyKey(requestUuid, playerUuid, endpoint, snapshot));
					return result;
				});
	}
}
