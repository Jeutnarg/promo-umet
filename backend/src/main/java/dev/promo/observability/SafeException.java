package dev.promo.observability;

import java.util.Arrays;

/** Preserves exception types and stack locations while excluding potentially sensitive messages. */
public final class SafeException extends RuntimeException {
	private SafeException(Throwable original, int depth) {
		super(
				original.getClass().getName(),
				depth < 4 && original.getCause() != null && original.getCause() != original
						? new SafeException(original.getCause(), depth + 1)
						: null);
		setStackTrace(
				Arrays.copyOf(original.getStackTrace(), Math.min(40, original.getStackTrace().length)));
	}

	public static SafeException redactMessages(Throwable original) {
		return new SafeException(original, 0);
	}

	public static boolean shouldReportStack() {
		var request = RequestDiagnostics.currentRequest();
		return request == null || request.markExceptionReported();
	}
}
