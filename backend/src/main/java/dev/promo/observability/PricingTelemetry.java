package dev.promo.observability;

import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Evaluation;
import dev.promo.domain.Model.Line;
import dev.promo.domain.Model.OptimizationResult;
import dev.promo.domain.PricingObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/** Publishes bounded pricing summaries and resource metrics, never individual candidate traces. */
public final class PricingTelemetry implements PricingObserver {
	private static final Logger LOG = LoggerFactory.getLogger(PricingTelemetry.class);
	private final MeterRegistry metrics;

	public PricingTelemetry(MeterRegistry metrics) {
		this.metrics = metrics;
	}

	@Override
	public void evaluationCompleted(
			Cart cart, int availablePromotions, Evaluation result, long elapsedNanos) {
		metrics
				.timer("sandbox.pricing.duration", "outcome", "success")
				.record(elapsedNanos, TimeUnit.NANOSECONDS);
		var request = RequestDiagnostics.currentRequest();
		if (request != null) {
			request.recordPricingEvaluation(elapsedNanos);
		}
		addCartSummary(LOG.atInfo(), cart, result, availablePromotions)
				.addKeyValue("duration_ms", elapsedNanos / 1_000_000.0)
				.log("pricing_evaluation_completed");
	}

	@Override
	public void optimizationCompleted(
			Cart cart,
			int availablePromotions,
			OptimizationResult result,
			Duration budget,
			long elapsedNanos) {
		metrics
				.timer("sandbox.optimization.duration", "mode", result.mode(), "outcome", "success")
				.record(elapsedNanos, TimeUnit.NANOSECONDS);
		metrics
				.counter("sandbox.optimization.candidates", "mode", result.mode())
				.increment(result.candidates());
		if (!result.optimalityProven()) {
			metrics.counter("sandbox.optimization.budget_exhausted", "mode", result.mode()).increment();
		}
		var request = RequestDiagnostics.currentRequest();
		if (request != null) {
			request.recordOptimization(elapsedNanos, result.candidates());
		}
		addCartSummary(LOG.atInfo(), cart, result.evaluation(), availablePromotions)
				.addKeyValue("mode", result.mode())
				.addKeyValue("duration_ms", elapsedNanos / 1_000_000.0)
				.addKeyValue("budget_ms", budget.toMillis())
				.addKeyValue("candidates", result.candidates())
				.addKeyValue("optimality_proven", result.optimalityProven())
				.addKeyValue("coverage_proven", result.coverageProven())
				.log("optimization_completed");
	}

	@Override
	public void calculationFailed(String operation, Throwable failure, long elapsedNanos) {
		String metric =
				operation.equals("pricing") ? "sandbox.pricing.duration" : "sandbox.optimization.duration";
		if (operation.equals("pricing")) {
			metrics.timer(metric, "outcome", "error").record(elapsedNanos, TimeUnit.NANOSECONDS);
		} else {
			metrics
					.timer(metric, "mode", "invalid_or_failed", "outcome", "error")
					.record(elapsedNanos, TimeUnit.NANOSECONDS);
		}
		var request = RequestDiagnostics.currentRequest();
		if (request != null) {
			if (operation.equals("pricing")) {
				request.recordPricingEvaluation(elapsedNanos);
			} else {
				request.recordOptimization(elapsedNanos, 0);
			}
		}
		boolean validation = failure instanceof IllegalArgumentException;
		var log =
				(validation ? LOG.atWarn() : LOG.atError())
						.addKeyValue("calculation", operation)
						.addKeyValue("duration_ms", elapsedNanos / 1_000_000.0)
						.addKeyValue("error_type", failure.getClass().getName());
		if (!validation && SafeException.shouldReportStack()) {
			log.setCause(SafeException.redactMessages(failure));
		}
		log.log("pricing_calculation_failed");
	}

	private LoggingEventBuilder addCartSummary(
			LoggingEventBuilder log, Cart cart, Evaluation result, int availablePromotions) {
		return log.addKeyValue(
						"cart_ref",
						RequestDiagnostics.createReference(cart.id() == null ? "unsaved" : cart.id()))
				.addKeyValue("line_count", cart.lines().size())
				.addKeyValue("unique_items", cart.lines().stream().map(Line::itemId).distinct().count())
				.addKeyValue("units", cart.lines().stream().mapToInt(Line::quantity).sum())
				.addKeyValue("available_promotions", availablePromotions)
				.addKeyValue("selected_promotions", cart.selections().size())
				.addKeyValue(
						"applied_promotions",
						result.steps().stream().map(step -> step.promotionId()).distinct().count())
				.addKeyValue("rejected_promotions", result.rejected().size())
				.addKeyValue("discounted_units", result.coveredUnits().size());
	}
}
