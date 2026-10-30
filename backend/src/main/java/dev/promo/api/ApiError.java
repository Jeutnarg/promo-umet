package dev.promo.api;

import dev.promo.observability.RequestDiagnostics;
import dev.promo.observability.SafeException;
import dev.promo.store.Conflict;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

/** Maps application failures to structured HTTP responses. */
@RestControllerAdvice
public class ApiError {
	private static final Logger LOG = LoggerFactory.getLogger(ApiError.class);

	/** Describes a stable API error code and customer-facing explanation. */
	public record Error(String code, String message) {}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Error> handleValidationError(IllegalArgumentException error) {
		logFailure(400, "validation", error);
		return createErrorResponse(400, "validation", error.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Error> handleMalformedBody(HttpMessageNotReadableException error) {
		logFailure(400, "validation", error);
		return createErrorResponse(400, "validation", "Malformed request body");
	}

	@ExceptionHandler(NoSuchElementException.class)
	ResponseEntity<Error> handleMissingResource(NoSuchElementException error) {
		logFailure(404, "not_found", error);
		return createErrorResponse(404, "not_found", "Resource not found");
	}

	@ExceptionHandler(Conflict.class)
	ResponseEntity<Error> handleConflict(Conflict error) {
		logFailure(409, "conflict", error);
		return createErrorResponse(409, "conflict", error.getMessage());
	}

	@ExceptionHandler(SdkException.class)
	ResponseEntity<Error> handleStorageFailure(SdkException error) {
		logFailure(503, "dependency_unavailable", error);
		return createErrorResponse(
				503, "dependency_unavailable", "Storage is unavailable; retry later");
	}

	@ExceptionHandler(Busy.class)
	ResponseEntity<Error> handleCapacityExceeded(Busy error) {
		logFailure(429, "busy", error);
		return createErrorResponse(429, "busy", "Calculation capacity is busy; retry shortly");
	}

	private ResponseEntity<Error> createErrorResponse(int status, String code, String message) {
		return ResponseEntity.status(status).body(new Error(code, message));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Error> handleUnexpectedFailure(Exception error) {
		if (error instanceof org.springframework.web.ErrorResponse rejected) {
			int status = rejected.getStatusCode().value();
			logFailure(status, "request_rejected", error);
			return createErrorResponse(status, "request_rejected", "The request could not be processed");
		}
		if (error instanceof org.springframework.security.access.AccessDeniedException) {
			logFailure(403, "forbidden", error);
			return createErrorResponse(403, "forbidden", "Access denied");
		}
		logFailure(500, "internal_error", error);
		return createErrorResponse(
				500,
				"internal_error",
				"An unexpected error occurred; use the X-Request-ID response header when reporting it");
	}

	private void logFailure(int status, String code, Throwable error) {
		var request = RequestDiagnostics.currentRequest();
		if (request != null) {
			request.recordErrorCode(code);
		}
		var log = status >= 500 ? LOG.atError() : LOG.atWarn();
		log.addKeyValue("http_status", status)
				.addKeyValue("error_code", code)
				.addKeyValue("error_type", error.getClass().getName());
		if (status >= 500 && SafeException.shouldReportStack()) {
			log.setCause(SafeException.redactMessages(error));
		}
		log.log("api_error");
	}

	/** Signals exhausted calculation capacity. */
	public static class Busy extends RuntimeException {}
}
