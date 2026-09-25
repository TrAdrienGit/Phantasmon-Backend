package com.mystaria.phantasmon_backend.auth;

/** Matches the OpenAPI {@code POST /auth/session} 200 response body. */
public record AuthSessionResponse(String accessToken, String refreshToken, long expiresIn) {
}
