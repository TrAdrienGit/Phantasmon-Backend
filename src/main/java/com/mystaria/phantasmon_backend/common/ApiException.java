package com.mystaria.phantasmon_backend.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A business-rule failure that must surface to the client as a structured
 * {@code {"error_code": ..., "details": {...}}} response, never raw text
 * (CAD Partie 3 §L, CONTEXT_CURSOR_BACKEND.md rule 4).
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String errorCode;
	private final Map<String, Object> details;

	public ApiException(HttpStatus status, String errorCode, Map<String, Object> details) {
		super(errorCode);
		this.status = status;
		this.errorCode = errorCode;
		this.details = details;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public Map<String, Object> getDetails() {
		return details;
	}
}
