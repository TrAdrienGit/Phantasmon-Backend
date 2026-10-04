package com.mystaria.phantasmon_backend.common;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every refusal leaves the API as an {@link ErrorResponse} ({@code error_code} + {@code details}), the request
 * format errors Spring raises before reaching a controller included (DEBT-2): Bean Validation failures become
 * {@code ERROR_VALIDATION_FAILED} with the offending fields in the API's snake_case, unreadable bodies, bad path
 * variables and missing query parameters {@code ERROR_MALFORMED_REQUEST}.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
		return ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(ex.getErrorCode(), ex.getDetails()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
		List<String> fields = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> snakeCase(error.getField()))
				.distinct()
				.sorted()
				.toList();
		return badRequest("ERROR_VALIDATION_FAILED", Map.of("fields", fields));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
		return badRequest("ERROR_MALFORMED_REQUEST", Map.of());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleBadParameter(MethodArgumentTypeMismatchException ex) {
		return badRequest("ERROR_MALFORMED_REQUEST", Map.of("parameter", snakeCase(ex.getName())));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
		return badRequest("ERROR_MALFORMED_REQUEST", Map.of("parameter", snakeCase(ex.getParameterName())));
	}

	private static ResponseEntity<ErrorResponse> badRequest(String errorCode, Map<String, Object> details) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(errorCode, details));
	}

	/** {@code serverId} → {@code server_id}, matching the JSON field names (global SNAKE_CASE strategy). */
	static String snakeCase(String javaName) {
		return javaName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
	}
}
