package com.mystaria.phantasmon_backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and validates the short-lived access token / longer-lived refresh
 * token pair produced by {@code POST /auth/session} (CAD Partie 2 §3.2). Both
 * are self-contained signed JWTs; there is no server-side revocation store in
 * V1 (not part of {@code PHANTASMON_DB_SCHEMA.md}).
 */
@Component
public class JwtService {

	private static final String CLAIM_USERNAME = "username";
	private static final String CLAIM_TYPE = "type";
	private static final String TYPE_ACCESS = "access";
	private static final String TYPE_REFRESH = "refresh";

	private final SecretKey key;
	private final Duration accessTtl;
	private final Duration refreshTtl;

	public JwtService(
			@Value("${phantasmon.jwt.secret}") String secret,
			@Value("${phantasmon.jwt.access-ttl}") Duration accessTtl,
			@Value("${phantasmon.jwt.refresh-ttl}") Duration refreshTtl) {
		byte[] keyBytes = isBase64(secret) ? Base64.getDecoder().decode(secret) : secret.getBytes(StandardCharsets.UTF_8);
		this.key = Keys.hmacShaKeyFor(keyBytes);
		this.accessTtl = accessTtl;
		this.refreshTtl = refreshTtl;
	}

	public String issueAccessToken(UUID playerUuid, String username) {
		return buildToken(playerUuid, TYPE_ACCESS, accessTtl, builder -> builder.claim(CLAIM_USERNAME, username));
	}

	public String issueRefreshToken(UUID playerUuid) {
		return buildToken(playerUuid, TYPE_REFRESH, refreshTtl, builder -> builder);
	}

	public long accessTokenTtlSeconds() {
		return accessTtl.toSeconds();
	}

	/**
	 * Parses and validates a token, returning its claims only if the signature
	 * and expiration are valid AND it is an access token (a refresh token is
	 * never accepted as API credentials).
	 */
	public Optional<Claims> parseAccessToken(String token) {
		return parseToken(token, TYPE_ACCESS);
	}

	/**
	 * Parses and validates a token, returning its claims only if the signature
	 * and expiration are valid AND it is a refresh token (an access token is
	 * never accepted at {@code POST /auth/refresh}).
	 */
	public Optional<Claims> parseRefreshToken(String token) {
		return parseToken(token, TYPE_REFRESH);
	}

	private Optional<Claims> parseToken(String token, String expectedType) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
			if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
				return Optional.empty();
			}
			return Optional.of(claims);
		} catch (JwtException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private String buildToken(UUID playerUuid, String type, Duration ttl, java.util.function.UnaryOperator<io.jsonwebtoken.JwtBuilder> customizer) {
		Instant now = Instant.now();
		var builder = Jwts.builder()
				.subject(playerUuid.toString())
				.claim(CLAIM_TYPE, type)
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(ttl)));
		return customizer.apply(builder).signWith(key).compact();
	}

	private static boolean isBase64(String value) {
		try {
			Base64.getDecoder().decode(value);
			return true;
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}
}
