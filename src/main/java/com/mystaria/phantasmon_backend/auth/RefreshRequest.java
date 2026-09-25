package com.mystaria.phantasmon_backend.auth;

import jakarta.validation.constraints.NotBlank;

/** Matches the OpenAPI-to-be {@code POST /auth/refresh} request body. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
