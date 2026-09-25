package com.mystaria.phantasmon_backend.common;

import java.util.Map;

/** Matches the OpenAPI {@code ErrorResponse} schema. */
public record ErrorResponse(String errorCode, Map<String, Object> details) {
}
