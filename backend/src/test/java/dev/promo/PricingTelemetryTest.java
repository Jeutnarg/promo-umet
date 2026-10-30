package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static dev.promo.Fixtures.createPromotion;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import dev.promo.observability.PricingTelemetry;
import dev.promo.observability.RequestDiagnostics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PricingTelemetryTest {
	@AfterEach
	void clearRequestContext() {
		RequestDiagnostics.finishRequest();
		MDC.clear();
	}

	@Test
	void optimizationPublishesOneSummaryRatherThanOneEventPerCandidate() {
		try (var capture = new LogCapture(PricingTelemetry.class);
				var registry = new TestMeterRegistry()) {
			var observer = new PricingTelemetry(registry);
			var engine = new PricingEngine(observer);
			var cart = createCart(List.of(createLine("private-item", 4, 1000)));
			var promotions = List.of(createPromotion("private-promotion", Scope.ITEMS, Strategy.BOGO, 0));
			var diagnostics = RequestDiagnostics.beginRequest("pricing-test");
			var result =
					new Optimizer(engine, observer)
							.findOptimalPromotions(cart, promotions, NOW, "suggest", Duration.ofSeconds(1));
			assertThat(result.candidates()).isGreaterThan(1);
			assertThat(capture.findEvents("optimization_completed")).hasSize(1);
			assertThat(capture.findEvents("pricing_evaluation_completed")).isEmpty();
			assertThat(diagnostics.getOptimizationCalls()).isEqualTo(1);
			assertThat(diagnostics.getOptimizationCandidates()).isEqualTo(result.candidates());
			assertThat(diagnostics.getPricingEvaluations()).isZero();
			var summary = capture.findEvents("optimization_completed").getFirst();
			assertThat(LogCapture.getField(summary, "unique_items")).isEqualTo(1L);
			assertThat(LogCapture.getField(summary, "applied_promotions")).isEqualTo(1L);
			engine.evaluateCartPrice(
					new Cart(
							cart.id(), cart.version(), cart.lines(), cart.shipping(), result.selections(), false),
					promotions,
					NOW);
			assertThat(capture.findEvents("pricing_evaluation_completed")).hasSize(1);
			assertThat(
							registry.get("sandbox.optimization.duration").tag("mode", "suggest").timer().count())
					.isEqualTo(1);
			assertThat(capture.renderCapturedData()).doesNotContain("private-item", "private-promotion");
			assertThat(registry.getMeters())
					.allMatch(
							meter ->
									meter.getId().getTags().stream()
											.noneMatch(
													tag ->
															tag.getKey().contains("request")
																	|| tag.getKey().contains("actor")
																	|| tag.getKey().contains("cart")));
		}
	}

	@Test
	void invalidCalculationsAreTimedWithoutLeakingValidationMessages() {
		try (var capture = new LogCapture(PricingTelemetry.class);
				var registry = new TestMeterRegistry()) {
			var engine = new PricingEngine(new PricingTelemetry(registry));
			org.assertj.core.api.Assertions.assertThatThrownBy(
							() -> engine.evaluateCartPrice(null, List.of(), NOW))
					.isInstanceOf(IllegalArgumentException.class);
			assertThat(capture.findEvents("pricing_calculation_failed")).hasSize(1);
			assertThat(registry.get("sandbox.pricing.duration").tag("outcome", "error").timer().count())
					.isEqualTo(1);
		}
	}
}
