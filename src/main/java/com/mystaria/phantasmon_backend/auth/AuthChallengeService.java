package com.mystaria.phantasmon_backend.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mystaria.phantasmon_backend.common.ApiException;

/**
 * One-time challenges for {@code POST /auth/session} (SEC-1, security audit 2026-10-04). The client must use a
 * challenge issued here as the Mojang {@code serverId}: a third-party Minecraft server the player joined only ever
 * knows its own {@code serverId}, so replaying that join proof to us no longer yields a token. Each challenge is
 * valid {@code phantasmon.auth.challenge-ttl} and consumed on first use, successful or not. In memory, like presence.
 */
@Service
public class AuthChallengeService {

	/** Hard cap on outstanding challenges, so an unauthenticated flood cannot grow the map without bound. */
	static final int MAX_OUTSTANDING = 10_000;

	private final SecureRandom random = new SecureRandom();
	private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
	private final Clock clock;
	private final Duration ttl;

	public AuthChallengeService(Clock clock, @Value("${phantasmon.auth.challenge-ttl:PT60S}") Duration ttl) {
		this.clock = clock;
		this.ttl = ttl;
	}

	/** 128 random bits, hex; also forgets every expired challenge. */
	public String issue() {
		Instant now = clock.instant();
		expiries.values().removeIf(expiry -> !expiry.isAfter(now));
		if (expiries.size() >= MAX_OUTSTANDING) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ERROR_AUTH_TOO_MANY_CHALLENGES", Map.of());
		}
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		String challenge = HexFormat.of().formatHex(bytes);
		expiries.put(challenge, now.plus(ttl));
		return challenge;
	}

	/** Whether {@code challenge} was issued here and is still valid; it can never be used again either way. */
	public boolean consume(String challenge) {
		if (challenge == null) {
			return false;
		}
		Instant expiry = expiries.remove(challenge);
		return expiry != null && expiry.isAfter(clock.instant());
	}

	int outstanding() {
		return expiries.size();
	}
}
