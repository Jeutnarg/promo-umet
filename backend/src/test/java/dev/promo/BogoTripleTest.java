package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static dev.promo.Fixtures.createPromotion;
import static dev.promo.Fixtures.withExclusivity;
import static dev.promo.Fixtures.withItemTarget;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Exclusivity;
import dev.promo.domain.Model.Line;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Selection;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BogoTripleTest {
	private final PricingEngine engine = new PricingEngine();

	@ParameterizedTest
	@CsvSource({"699,0", "700,0", "701,700", "1000,700"})
	void requiresStrictlyCheaperCombinedAddOnsAfterDiscounts(
			long sponsorBalance, long expectedSaving) {
		var discount =
				withItemTarget(
						createPromotion("first", Scope.ONE_ITEM, Strategy.FLAT, 1000 - sponsorBalance), "a");
		var triple = createPromotion("triple", Scope.ORDER, Strategy.BOGO_TRIPLE, 0);
		var cart =
				createCart(
						List.of(createLine("a", 1, 1000), createLine("b", 1, 300), createLine("c", 1, 400)),
						new Selection("first", "a", List.of()),
						new Selection("triple"));
		var result = engine.evaluateCartPrice(cart, List.of(triple, discount), NOW);
		assertThat(
						result.steps().stream()
								.filter(step -> step.promotionId().equals("triple"))
								.mapToLong(step -> step.saving())
								.sum())
				.isEqualTo(expectedSaving);
		assertThat(result.shipping()).isEqualTo(1000);
		assertThat(result.total()).isEqualTo(sponsorBalance + 700 - expectedSaving + 1000);
	}

	@Test
	void usesDiscountedAddOnPricesAndRecordsBothAllocations() {
		var discount = withItemTarget(createPromotion("first", Scope.ITEM, Strategy.PERCENT, 0), "b");
		var triple = createPromotion("triple", Scope.ITEMS, Strategy.BOGO_TRIPLE, 0);
		var result =
				engine.evaluateCartPrice(
						createCart(
								List.of(createLine("a", 1, 1000), createLine("b", 2, 550)),
								new Selection("first", "b", List.of()),
								new Selection("triple")),
						List.of(triple, discount),
						NOW);
		assertThat(result.steps().getLast().saving()).isEqualTo(880);
		assertThat(result.steps().getLast().allocations())
				.hasSize(2)
				.allMatch(allocation -> allocation.lineId().equals("b"));
		assertThat(result.coveredUnits()).hasSize(2);
	}

	@ParameterizedTest
	@EnumSource(Scope.class)
	void respectsDeclaredScopeAndNeverUsesShipping(Scope scope) {
		var triple = createPromotion("triple", scope, Strategy.BOGO_TRIPLE, 0);
		var result =
				engine.evaluateCartPrice(
						createCart(
								List.of(createLine("a", 1, 1000), createLine("b", 2, 300)),
								new Selection("triple")),
						List.of(triple),
						NOW);
		assertThat(result.savings()).isEqualTo(scope == Scope.ITEMS || scope == Scope.ORDER ? 600 : 0);
		assertThat(result.shipping()).isEqualTo(1000);
	}

	@Test
	void rejectsRepeatedOutOfScopeIncompleteAndNonqualifyingGroups() {
		var cart = createCart(List.of(createLine("a", 2, 1000), createLine("b", 4, 300)));
		var triple = createPromotion("triple", Scope.ITEMS, Strategy.BOGO_TRIPLE, 0);
		for (var groups :
				List.of(
						List.of(List.of("a:0000", "b:0000", "b:0000")),
						List.of(List.of("a:0000", "b:0000", "foreign:0000")),
						List.of(List.of("a:0000", "b:0000")),
						List.of(List.of("b:0000", "b:0001", "b:0002")),
						List.of(List.of("a:0000", "b:0000", "b:0001")))) {
			var selected =
					new Cart(
							cart.id(),
							cart.version(),
							cart.lines(),
							cart.shipping(),
							List.of(new Selection("triple", null, groups)),
							false);
			var result = engine.evaluateCartPrice(selected, List.of(triple), NOW);
			assertThat(result.steps()).isEmpty();
			assertThat(result.rejected()).hasSize(1);
		}
		var complete =
				new Selection(
						"triple",
						null,
						List.of(List.of("b:0000", "a:0000", "b:0001"), List.of("a:0001", "b:0002", "b:0003")));
		assertThat(
						engine
								.evaluateCartPrice(
										new Cart(cart.id(), 1, cart.lines(), 1000, List.of(complete), false),
										List.of(triple),
										NOW)
								.savings())
				.isEqualTo(1200);
	}

	@Test
	void reservesPaidQualifyingUnitsForUnitExclusivity() {
		var triple =
				withExclusivity(
						createPromotion("a-triple", Scope.ITEMS, Strategy.BOGO_TRIPLE, 0), Exclusivity.UNITS);
		var later = createPromotion("later", Scope.ORDER, Strategy.FLAT, 100);
		var result =
				engine.evaluateCartPrice(
						createCart(
								List.of(createLine("a", 1, 1000), createLine("b", 2, 300)),
								new Selection(triple.id()),
								new Selection(later.id())),
						List.of(later, triple),
						NOW);
		assertThat(result.savings()).isEqualTo(600);
		assertThat(result.rejected()).anyMatch(rejection -> rejection.reason().contains("exclusive"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"suggest", "swindle"})
	void matchesExhaustiveSmallCartGroupingsAndReproducesResult(String mode) {
		for (long[] prices :
				List.of(
						new long[] {200, 300, 400, 500, 600, 1000},
						new long[] {100, 100, 300, 400, 700, 1500},
						new long[] {0, 200, 300, 500, 900, 1200})) {
			var lines = new ArrayList<Line>();
			for (int index = 0; index < prices.length; index++)
				lines.add(createLine("line" + index, 1, prices[index]));
			var cart = createCart(lines);
			var triple = createPromotion("triple", Scope.ITEMS, Strategy.BOGO_TRIPLE, 0);
			var result =
					new Optimizer(engine)
							.findOptimalPromotions(cart, List.of(triple), NOW, mode, Duration.ofSeconds(2));
			var oracle = new Oracle(prices, mode);
			oracle.enumerateGroups(0, 0, 0);
			assertThat(result.optimalityProven()).isTrue();
			assertThat(result.evaluation().savings()).isEqualTo(oracle.bestSaving);
			assertThat(result.evaluation().coveredUnits().size()).isEqualTo(oracle.bestCoverage);
			assertThat(
							engine
									.evaluateCartPrice(
											createCart(lines, result.selections().toArray(Selection[]::new)),
											List.of(triple),
											NOW)
									.total())
					.isEqualTo(result.evaluation().total());
		}
	}

	@Test
	void keepsLargeTripleSearchBoundedAndReturnsVerifiedCandidate() {
		var cart = createCart(List.of(createLine("large", 300, 10000), createLine("small", 700, 100)));
		var promotions = List.of(createPromotion("triple", Scope.ITEMS, Strategy.BOGO_TRIPLE, 0));
		long started = System.nanoTime();
		var result =
				new Optimizer(engine)
						.findOptimalPromotions(cart, promotions, NOW, "suggest", Duration.ofMillis(150));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
		assertThat(result.optimalityProven()).isFalse();
		assertThat(result.evaluation().savings()).isPositive();
		assertThat(
						engine
								.evaluateCartPrice(
										createCart(cart.lines(), result.selections().toArray(Selection[]::new)),
										promotions,
										NOW)
								.total())
				.isEqualTo(result.evaluation().total());
	}

	/** Enumerates all disjoint legal groups, independently of production pairing/search code. */
	private static class Oracle {
		final long[] prices;
		final String mode;
		long bestSaving;
		int bestCoverage;

		Oracle(long[] prices, String mode) {
			this.prices = prices;
			this.mode = mode;
		}

		void enumerateGroups(int used, long saving, int coverage) {
			boolean extendable = false;
			for (int a = 0; a < prices.length; a++)
				for (int b = a + 1; b < prices.length; b++)
					for (int c = b + 1; c < prices.length; c++) {
						int mask = (1 << a) | (1 << b) | (1 << c);
						long largest = Math.max(prices[a], Math.max(prices[b], prices[c]));
						long discount = prices[a] + prices[b] + prices[c] - largest;
						if ((used & mask) == 0 && discount < largest) {
							extendable = true;
							enumerateGroups(
									used | mask,
									saving + discount,
									coverage
											+ (prices[a] > 0 ? 1 : 0)
											+ (prices[b] > 0 ? 1 : 0)
											+ (prices[c] > 0 ? 1 : 0)
											- 1);
						}
					}
			if (extendable) return;
			boolean better =
					mode.equals("suggest")
							? saving > bestSaving
							: coverage > bestCoverage || (coverage == bestCoverage && saving < bestSaving);
			if (better) {
				bestSaving = saving;
				bestCoverage = coverage;
			}
		}
	}
}
