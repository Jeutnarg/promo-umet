package dev.promo.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.MDC;

/** Holds bounded diagnostic counters for one synchronous request without retaining payloads. */
public final class RequestDiagnostics {
	private static final ThreadLocal<RequestDiagnostics> CURRENT = new ThreadLocal<>();
	private String actorReference = "anonymous";
	private long databaseCalls;
	private long databaseAttempts;
	private long databaseNanos;
	private long databaseFailures;
	private long pricingEvaluations;
	private long pricingNanos;
	private long optimizationCalls;
	private long optimizationCandidates;
	private long optimizationNanos;
	private boolean exceptionReported;
	private String errorCode = "none";

	public static RequestDiagnostics beginRequest(String requestId) {
		var diagnostics = new RequestDiagnostics();
		CURRENT.set(diagnostics);
		MDC.put("request_id", requestId);
		MDC.put("actor_ref", "anonymous");
		MDC.put("actor_type", "anonymous");
		return diagnostics;
	}

	public static RequestDiagnostics currentRequest() {
		return CURRENT.get();
	}

	public static void finishRequest() {
		CURRENT.remove();
	}

	public void identifyActor(String issuer, String subject) {
		actorReference = createReference(issuer + "|" + subject);
		MDC.put("actor_ref", actorReference);
	}

	public static String createReference(String value) {
		try {
			return HexFormat.of()
					.formatHex(
							MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
					.substring(0, 20);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	public void recordDatabaseCall(long elapsedNanos, long attempts, boolean failed) {
		databaseCalls++;
		databaseAttempts += attempts;
		databaseNanos += elapsedNanos;
		if (failed) {
			databaseFailures++;
		}
	}

	public void recordPricingEvaluation(long elapsedNanos) {
		pricingEvaluations++;
		pricingNanos += elapsedNanos;
	}

	public void recordOptimization(long elapsedNanos, long candidates) {
		optimizationCalls++;
		optimizationNanos += elapsedNanos;
		optimizationCandidates += candidates;
	}

	public boolean markExceptionReported() {
		boolean first = !exceptionReported;
		exceptionReported = true;
		return first;
	}

	public void recordErrorCode(String code) {
		errorCode = code;
	}

	public String getActorReference() {
		return actorReference;
	}

	public long getDatabaseCalls() {
		return databaseCalls;
	}

	public long getDatabaseAttempts() {
		return databaseAttempts;
	}

	public long getDatabaseNanos() {
		return databaseNanos;
	}

	public long getDatabaseFailures() {
		return databaseFailures;
	}

	public long getPricingEvaluations() {
		return pricingEvaluations;
	}

	public long getPricingNanos() {
		return pricingNanos;
	}

	public long getOptimizationCalls() {
		return optimizationCalls;
	}

	public long getOptimizationCandidates() {
		return optimizationCandidates;
	}

	public long getOptimizationNanos() {
		return optimizationNanos;
	}

	public String getErrorCode() {
		return errorCode;
	}
}
