package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static dev.promo.Fixtures.createPromotion;
import static dev.promo.Fixtures.withExclusivity;
import static dev.promo.Fixtures.withItemTarget;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promo.domain.Model.Allocation;
import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Details;
import dev.promo.domain.Model.Exclusivity;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Rejection;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Selection;
import dev.promo.domain.Model.Step;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingEngineTest {
	private final PricingEngine engine = new PricingEngine();

	@Test
	void sketchExample() {
		var cart =
				createCart(
						List.of(createLine("one", 4, 1000), createLine("two", 2, 1500)),
						new Selection("bogo"),
						new Selection("percent"));
		var result =
				engine.evaluateCartPrice(
						cart,
						List.of(
								withItemTarget(createPromotion("bogo", Scope.ITEM, Strategy.BOGO, 0), "one"),
								withItemTarget(createPromotion("percent", Scope.ITEM, Strategy.PERCENT, 0), "two")),
						NOW);
		assertThat(result.total()).isEqualTo(5400);
		assertThat(result.steps()).extracting(Step::saving).containsExactly(2000L, 600L);
	}

	@ParameterizedTest
	@CsvSource({
		"ONE_ITEM,500,3500",
		"ITEM,500,2500",
		"ITEMS,500,3500",
		"ORDER,500,3500",
		"ONE_ITEM,9000,1000",
		"ITEM,9000,1000",
		"ORDER,9000,0"
	})
	void flatScopesAndFloors(Scope scope, long amount, long expected) {
		var result =
				engine.evaluateCartPrice(
						createCart(List.of(createLine("a", 3, 1000)), new Selection("p")),
						List.of(createPromotion("p", scope, Strategy.FLAT, amount)),
						NOW);
		assertThat(result.total()).isEqualTo(expected);
		assertThat(
						result.steps().stream()
								.flatMap(s -> s.allocations().stream())
								.mapToLong(Allocation::saving)
								.sum())
				.isEqualTo(result.savings());
	}

	@ParameterizedTest
	@CsvSource({"0,0", "1,0", "2,0", "3,1", "5,1", "8,2", "1000,200"})
	void percentageRounding(long price, long saving) {
		var result =
				engine.evaluateCartPrice(
						createCart(List.of(createLine("a", 1, price)), new Selection("p")),
						List.of(createPromotion("p", Scope.ITEM, Strategy.PERCENT, 0)),
						NOW);
		assertThat(result.savings()).isEqualTo(saving);
	}

	@ParameterizedTest
	@CsvSource({"500,2500", "1000,4000", "1500,4000"})
	void fixedPriceNeverRaisesPrice(long fixed, long expected) {
		var result =
				engine.evaluateCartPrice(
						createCart(List.of(createLine("a", 3, 1000)), new Selection("p")),
						List.of(createPromotion("p", Scope.ITEM, Strategy.FIXED_PRICE, fixed)),
						NOW);
		assertThat(result.savings()).isEqualTo(4000 - expected);
	}

	@Test
	void stylesAreOrderedAfterScopeRegardlessOfInputOrder() {
		var result =
				engine.evaluateCartPrice(
						createCart(
								List.of(createLine("a", 1, 1000)),
								new Selection("percentage"),
								new Selection("flat")),
						List.of(
								createPromotion("percentage", Scope.ITEM, Strategy.PERCENT, 0),
								createPromotion("flat", Scope.ITEM, Strategy.FLAT, 200)),
						NOW);
		assertThat(result.steps()).extracting(Step::promotionId).containsExactly("flat", "percentage");
		assertThat(result.total()).isEqualTo(1640);
	}

	@Test
	void goalsShareFrozenSubtotalAndUseStrictThreshold() {
		var first = createPromotion("a", Scope.ITEMS, Strategy.FLAT_GOAL, 400);
		first =
				new Promotion(
						first.id(),
						1,
						first.name(),
						first.scope(),
						first.exclusivity(),
						first.strategy(),
						new Details(400L, null, 900L, null),
						List.of(),
						List.of(),
						null,
						true,
						1);
		var second =
				new Promotion(
						"b",
						1,
						"b",
						Scope.ORDER,
						Exclusivity.STACKABLE,
						Strategy.SHIPPING_GOAL,
						new Details(null, null, 900L, null),
						List.of(),
						List.of(),
						null,
						true,
						1);
		var result =
				engine.evaluateCartPrice(
						createCart(List.of(createLine("a", 1, 1000)), new Selection("a"), new Selection("b")),
						List.of(first, second),
						NOW);
		assertThat(result.total()).isEqualTo(600);
		assertThat(
						engine
								.evaluateCartPrice(
										createCart(List.of(createLine("a", 1, 900)), new Selection("a")),
										List.of(first),
										NOW)
								.steps())
				.isEmpty();
	}

	@Test
	void centAllocationIsStableAndCoversOnlyPositiveSavings() {
		var result =
				engine.evaluateCartPrice(
						createCart(List.of(createLine("b", 1, 1), createLine("a", 1, 1)), new Selection("p")),
						List.of(createPromotion("p", Scope.ITEMS, Strategy.FLAT, 1)),
						NOW);
		assertThat(result.coveredUnits()).containsExactly("a:0000");
		assertThat(result.uncoveredUnits()).containsExactly("b:0000");
	}

	@ParameterizedTest
	@CsvSource({"STACKABLE,2", "UNITS,1", "ORDER,1"})
	void exclusivity(Exclusivity exclusivity, int count) {
		var result =
				engine.evaluateCartPrice(
						createCart(List.of(createLine("a", 2, 1000)), new Selection("a"), new Selection("b")),
						List.of(
								withExclusivity(createPromotion("a", Scope.ITEM, Strategy.FLAT, 100), exclusivity),
								createPromotion("b", Scope.ITEM, Strategy.PERCENT, 0)),
						NOW);
		assertThat(result.steps()).hasSize(count);
	}

	@Test
	void expiredAtBoundaryAndInvalidPairingAreExplained() {
		var expired =
				new Promotion(
						"expired",
						1,
						"expired",
						Scope.ITEM,
						Exclusivity.STACKABLE,
						Strategy.FLAT,
						new Details(100L, null, null, null),
						List.of(),
						List.of(),
						NOW,
						true,
						1);
		var result =
				engine.evaluateCartPrice(
						createCart(
								List.of(createLine("a", 2, 1000)),
								new Selection("expired"),
								new Selection("bogo", null, List.of(List.of("a:0000", "a:0000")))),
						List.of(expired, createPromotion("bogo", Scope.ITEM, Strategy.BOGO, 0)),
						NOW);
		assertThat(result.rejected())
				.extracting(Rejection::reason)
				.contains("Promotion expired", "BOGO contains an invalid or repeated unit");
	}

	@Test
	void optimizerMatchesExhaustiveSubsets() {
		var cart = createCart(List.of(createLine("a", 3, 1000)));
		var promotions =
				List.of(
						createPromotion("flat", Scope.ITEMS, Strategy.FLAT, 200),
						createPromotion("percent", Scope.ITEMS, Strategy.PERCENT, 0),
						withExclusivity(
								createPromotion("exclusive", Scope.ITEMS, Strategy.FLAT, 500), Exclusivity.ORDER));
		long minimum = Long.MAX_VALUE;
		for (int mask = 0; mask < 8; mask++) {
			var selections = new ArrayList<Selection>();
			for (int i = 0; i < 3; i++) {
				if ((mask & (1 << i)) != 0) {
					selections.add(new Selection(promotions.get(i).id()));
				}
			}
			var result =
					engine.evaluateCartPrice(
							new Cart(cart.id(), 1, cart.lines(), 1000, selections, false), promotions, NOW);
			if (result.rejected().isEmpty()) {
				minimum = Math.min(minimum, result.total());
			}
		}
		var result =
				new Optimizer(engine)
						.findOptimalPromotions(cart, promotions, NOW, "suggest", Duration.ofSeconds(2));
		assertThat(result.optimalityProven()).isTrue();
		assertThat(result.evaluation().total()).isEqualTo(minimum);
	}

	@Test
	void swindlePrefersCoverageBeforeMinimumSavings() {
		var result =
				new Optimizer(engine)
						.findOptimalPromotions(
								createCart(List.of(createLine("a", 4, 1000))),
								List.of(
										createPromotion("bogo", Scope.ITEMS, Strategy.BOGO, 0),
										createPromotion("penny", Scope.ITEMS, Strategy.FLAT, 4)),
								NOW,
								"swindle",
								Duration.ofSeconds(2));
		assertThat(result.evaluation().coveredUnits()).hasSize(4);
		assertThat(result.evaluation().savings()).isEqualTo(4);
	}

	@Test
	void bogoSearchFindsBothMaximumAndMinimumSavingsPairings() {
		var cart =
				createCart(
						List.of(
								createLine("a", 1, 100),
								createLine("b", 1, 200),
								createLine("c", 1, 300),
								createLine("d", 1, 400)));
		var promotions = List.of(createPromotion("bogo", Scope.ITEMS, Strategy.BOGO, 0));
		var optimizer = new Optimizer(engine);
		assertThat(
						optimizer
								.findOptimalPromotions(cart, promotions, NOW, "suggest", Duration.ofSeconds(2))
								.evaluation()
								.savings())
				.isEqualTo(400);
		assertThat(
						optimizer
								.findOptimalPromotions(cart, promotions, NOW, "swindle", Duration.ofSeconds(2))
								.evaluation()
								.savings())
				.isEqualTo(300);
	}

	@Test
	void timeoutDoesNotClaimOptimality() {
		var result =
				new Optimizer(engine)
						.findOptimalPromotions(
								createCart(List.of(createLine("a", 20, 1000))),
								List.of(createPromotion("bogo", Scope.ITEMS, Strategy.BOGO, 0)),
								NOW,
								"suggest",
								Duration.ZERO);
		assertThat(result.optimalityProven()).isFalse();
		assertThat(result.coverageProven()).isFalse();
	}
}
