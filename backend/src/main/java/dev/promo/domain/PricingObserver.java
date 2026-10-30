package dev.promo.domain;

import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Evaluation;
import dev.promo.domain.Model.OptimizationResult;
import java.time.Duration;

/** Reports high-level completed work without coupling pricing logic to logging frameworks. */
public interface PricingObserver {
	PricingObserver NONE = new PricingObserver() {};

	default void evaluationCompleted(
			Cart cart, int availablePromotions, Evaluation result, long elapsedNanos) {}

	default void optimizationCompleted(
			Cart cart,
			int availablePromotions,
			OptimizationResult result,
			Duration budget,
			long elapsedNanos) {}

	default void calculationFailed(String operation, Throwable failure, long elapsedNanos) {}
}
