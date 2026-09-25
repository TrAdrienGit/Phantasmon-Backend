package com.mystaria.phantasmon_backend.auth;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Matches the OpenAPI {@code POST /auth/session} request body. */
public record AuthSessionRequest(
		@NotNull UUID uuid,
		@NotBlank String username,
		@NotBlank String serverId) {
}
