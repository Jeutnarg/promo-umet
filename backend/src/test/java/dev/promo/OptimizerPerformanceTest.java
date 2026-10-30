package dev.promo;

import static dev.promo.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Details;
import dev.promo.domain.Model.Exclusivity;
import dev.promo.domain.Model.Line;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OptimizerPerformanceTest {

	@ParameterizedTest
	@CsvSource({"suggest,100,10", "swindle,100,10", "suggest,50,10", "swindle,50,10"})
	void maximumPromotionSearchRemainsBoundedAndReturnsReproduciblePrices(
			String mode, int lineCount, int quantity) {
		var engine = new PricingEngine();
		var lines =
				IntStream.range(0, lineCount)
						.mapToObj(
								index ->
										new Line(
												"line-" + index,
												"item-" + index,
												"Item " + index,
												quantity,
												1000 + index,
												"category-" + index % 10))
						.toList();
		var cart = new Cart("stress", 1, lines, 1000, List.of(), false);
		var strategies =
				List.of(
						Strategy.BOGO,
						Strategy.FLAT,
						Strategy.PERCENT,
						Strategy.FIXED_PRICE,
						Strategy.FLAT_GOAL);
		var promotions =
				IntStream.range(0, 50)
						.mapToObj(
								index ->
										new Promotion(
												String.format(Locale.ROOT, "promotion-%02d", index),
												1,
												"Offer " + index,
												Scope.ITEMS,
												Exclusivity.STACKABLE,
												strategies.get(index % strategies.size()),
												new Details(100L, 100, 1000L, 2),
												List.of(),
												List.of(),
												null,
												true,
												1))
						.toList();
		long started = System.nanoTime();
		var result =
				assertTimeoutPreemptively(
						Duration.ofSeconds(8),
						() ->
								new Optimizer(engine)
										.findOptimalPromotions(cart, promotions, NOW, mode, Duration.ofSeconds(2)));
		long milliseconds = Duration.ofNanos(System.nanoTime() - started).toMillis();
		System.out.printf(
				Locale.ROOT,
				"Optimizer stress: mode=%s units=%d promotions=%d elapsedMs=%d candidates=%d proven=%s%n",
				mode,
				lineCount * quantity,
				promotions.size(),
				milliseconds,
				result.candidates(),
				result.optimalityProven());
		var replay =
				new Cart(cart.id(), cart.version(), lines, cart.shipping(), result.selections(), false);
		assertThat(engine.evaluateCartPrice(replay, promotions, NOW)).isEqualTo(result.evaluation());
		assertThat(result.evaluation().rejected()).isEmpty();
		assertThat(result.evaluation().total())
				.isBetween(0L, engine.evaluateCartPrice(cart, promotions, NOW).total());
		assertThat(result.candidates()).isPositive();
		assertThat(result.optimalityProven()).isFalse();
	}
}
