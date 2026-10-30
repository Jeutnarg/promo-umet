package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static dev.promo.Fixtures.createPromotion;
import static dev.promo.Fixtures.withExclusivity;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promo.domain.Model.Allocation;
import dev.promo.domain.Model.Details;
import dev.promo.domain.Model.Exclusivity;
import dev.promo.domain.Model.Line;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Rejection;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Selection;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingInvariantsTest {
	private final PricingEngine engine = new PricingEngine();

	@ParameterizedTest
	@CsvSource({"1,1,200", "2,1,200", "2,2,200", "2,3,0", "3,2,200", "3,3,200", "3,4,0"})
	void rainbowCategoryBoundaries(int categories, int threshold, long expectedSavings) {
		var lines = new ArrayList<Line>();
		for (int i = 0; i < categories; i++) {
			lines.add(new Line("line" + i, "item" + i, "Item", 1, i == 0 ? 1000 : 0, "category" + i));
		}
		var promotion =
				new Promotion(
						"rainbow",
						1,
						"Rainbow",
						Scope.ITEMS,
						Exclusivity.STACKABLE,
						Strategy.RAINBOW_PERCENT,
						new Details(null, 2000, null, threshold),
						List.of(),
						List.of(),
						null,
						true,
						1);
		var evaluation =
				engine.evaluateCartPrice(
						createCart(lines, new Selection("rainbow")), List.of(promotion), NOW);
		assertThat(evaluation.savings()).isEqualTo(expectedSavings);
	}

	@Test
	void allAllocationsConserveMoneyAcrossStrategiesAndScopes() {
		var random = new Random(443);
		for (var scope : Scope.values()) {
			for (var strategy : List.of(Strategy.FLAT, Strategy.PERCENT, Strategy.FIXED_PRICE)) {
				for (int trial = 0; trial < 20; trial++) {
					var input =
							createCart(
									List.of(
											createLine("a", 3, random.nextInt(10000)),
											createLine("b", 2, random.nextInt(10000))),
									new Selection("rule"));
					var result =
							engine.evaluateCartPrice(
									input,
									List.of(createPromotion("rule", scope, strategy, random.nextInt(10000))),
									NOW);
					assertThat(result.total())
							.isBetween(0L, result.originalMerchandise() + result.originalShipping());
					assertThat(result.savings() + result.total())
							.isEqualTo(result.originalMerchandise() + result.originalShipping());
					assertThat(
									result.steps().stream()
											.flatMap(step -> step.allocations().stream())
											.mapToLong(Allocation::saving)
											.sum())
							.isEqualTo(result.savings());
					assertThat(result.coveredUnits().size() + result.uncoveredUnits().size()).isEqualTo(5);
				}
			}
		}
	}

	@Test
	void bogoOddUnitSearchCanChooseWhichUnitRemainsUnpaired() {
		var input =
				createCart(
						List.of(createLine("a", 1, 100), createLine("b", 1, 200), createLine("c", 1, 300)));
		var promotions = List.of(createPromotion("bogo", Scope.ITEMS, Strategy.BOGO, 0));
		var optimizer = new Optimizer(engine);
		var best =
				optimizer.findOptimalPromotions(input, promotions, NOW, "suggest", Duration.ofSeconds(2));
		var worst =
				optimizer.findOptimalPromotions(input, promotions, NOW, "swindle", Duration.ofSeconds(2));
		assertThat(best.evaluation().savings()).isEqualTo(200);
		assertThat(worst.evaluation().savings()).isEqualTo(100);
		assertThat(best.optimalityProven()).isTrue();
		assertThat(worst.evaluation().coveredUnits()).hasSize(1);
	}

	@Test
	void targetsIntersectAndNarrowPromotionsSearchEveryLine() {
		var lines =
				List.of(
						new Line("a", "a", "First", 1, 100, "eligible"),
						new Line("b", "b", "Second", 1, 1000, "eligible"));
		var p = createPromotion("percentage", Scope.ONE_ITEM, Strategy.PERCENT, 0);
		var result =
				new Optimizer(engine)
						.findOptimalPromotions(
								createCart(lines), List.of(p), NOW, "suggest", Duration.ofSeconds(2));
		assertThat(result.selections().getFirst().lineId()).isEqualTo("b");
		var excluded =
				new Promotion(
						"excluded",
						1,
						"Excluded",
						Scope.ITEMS,
						Exclusivity.STACKABLE,
						Strategy.FLAT,
						new Details(100L, null, null, null),
						List.of("a"),
						List.of("other"),
						null,
						true,
						1);
		assertThat(
						engine
								.evaluateCartPrice(
										createCart(lines, new Selection("excluded")), List.of(excluded), NOW)
								.steps())
				.isEmpty();
	}

	@Test
	void bogoQualifyingUnitsAreReservedByUnitExclusivity() {
		var bogo =
				withExclusivity(createPromotion("a", Scope.ITEMS, Strategy.BOGO, 0), Exclusivity.UNITS);
		var flat = createPromotion("b", Scope.ITEMS, Strategy.FLAT, 100);
		var result =
				engine.evaluateCartPrice(
						createCart(List.of(createLine("a", 2, 1000)), new Selection("a"), new Selection("b")),
						List.of(bogo, flat),
						NOW);
		assertThat(result.steps()).hasSize(1);
		assertThat(result.rejected())
				.extracting(Rejection::reason)
				.containsExactly("Conflicts with an exclusive promotion");
	}
}
