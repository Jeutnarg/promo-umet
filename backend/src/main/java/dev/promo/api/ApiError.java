package dev.promo.api;

import dev.promo.store.Conflict;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

/** Maps application failures to structured HTTP responses. */
@RestControllerAdvice
public class ApiError {
	/** Describes a stable API error code and customer-facing explanation. */
	public record Error(String code, String message) {}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Error> handleValidationError(IllegalArgumentException error) {
		return createErrorResponse(400, "validation", error.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Error> handleMalformedBody() {
		return createErrorResponse(400, "validation", "Malformed request body");
	}

	@ExceptionHandler(NoSuchElementException.class)
	ResponseEntity<Error> handleMissingResource() {
		return createErrorResponse(404, "not_found", "Resource not found");
	}

	@ExceptionHandler(Conflict.class)
	ResponseEntity<Error> handleConflict(Conflict error) {
		return createErrorResponse(409, "conflict", error.getMessage());
	}

	@ExceptionHandler(SdkException.class)
	ResponseEntity<Error> handleStorageFailure() {
		return createErrorResponse(
				503, "dependency_unavailable", "Storage is unavailable; retry later");
	}

	@ExceptionHandler(Busy.class)
	ResponseEntity<Error> handleCapacityExceeded() {
		return createErrorResponse(429, "busy", "Calculation capacity is busy; retry shortly");
	}

	private ResponseEntity<Error> createErrorResponse(int status, String code, String message) {
		return ResponseEntity.status(status).body(new Error(code, message));
	}

	/** Signals exhausted calculation capacity. */
	public static class Busy extends RuntimeException {}
}
