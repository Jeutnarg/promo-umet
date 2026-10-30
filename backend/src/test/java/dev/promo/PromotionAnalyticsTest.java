package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static dev.promo.Fixtures.createPromotion;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promo.domain.Model.Order;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Purchase;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Selection;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.PricingEngine;
import dev.promo.domain.PromotionAnalytics;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PromotionAnalyticsTest {
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 10})
	void usageAndCostCountRecordedApplicationsIncludingShipping(int purchaseCount) {
		var flat = createPromotion("flat", Scope.ITEMS, Strategy.FLAT, 200);
		var shipping = createPromotion("shipping", Scope.ORDER, Strategy.SHIPPING_GOAL, 0);
		var unused = createPromotion("unused", Scope.ITEMS, Strategy.PERCENT, 0);
		var promotions = List.of(flat, shipping, unused);
		var purchases =
				IntStream.range(0, purchaseCount)
						.mapToObj(
								index ->
										createPurchase(
												"purchase-" + index,
												promotions,
												List.of(new Selection("flat"), new Selection("shipping"))))
						.toList();
		var dashboard = new PromotionAnalytics().createDashboard(purchases, promotions, NOW);
		assertThat(dashboard.purchaseCount()).isEqualTo(purchaseCount);
		assertThat(dashboard.totalPromotionCost()).isEqualTo(1200L * purchaseCount);
		assertThat(dashboard.totalOrderValue()).isEqualTo(1800L * purchaseCount);
		var usage =
				dashboard.promotions().stream()
						.filter(row -> row.promotionId().equals("flat"))
						.findFirst()
						.orElseThrow();
		assertThat(usage.purchaseCount()).isEqualTo(purchaseCount);
		assertThat(usage.discountedUnits()).isEqualTo(2L * purchaseCount);
		assertThat(usage.cost()).isEqualTo(200L * purchaseCount);
		var shippingUsage =
				dashboard.promotions().stream()
						.filter(row -> row.promotionId().equals("shipping"))
						.findFirst()
						.orElseThrow();
		assertThat(shippingUsage.shippingCost()).isEqualTo(1000L * purchaseCount);
		assertThat(shippingUsage.discountedUnits()).isZero();
		assertThat(
						dashboard.promotions().stream()
								.filter(row -> row.promotionId().equals("unused"))
								.findFirst()
								.orElseThrow()
								.cost())
				.isZero();
	}

	@Test
	void editingOrRemovingPromotionDefinitionsDoesNotRewriteHistoricalCosts() {
		var original = createPromotion("offer", Scope.ITEMS, Strategy.FLAT, 200);
		var edited =
				new Promotion(
						original.id(),
						2,
						"Renamed offer",
						original.scope(),
						original.exclusivity(),
						original.strategy(),
						new dev.promo.domain.Model.Details(500L, null, null, null),
						List.of(),
						List.of(),
						null,
						false,
						1);
		var first = createPurchase("first", List.of(original), List.of(new Selection("offer")));
		var second = createPurchase("second", List.of(edited), List.of());
		var dashboard =
				new PromotionAnalytics().createDashboard(List.of(first, second), List.of(edited), NOW);
		assertThat(dashboard.promotions().getFirst().name()).isEqualTo("Renamed offer");
		assertThat(dashboard.promotions().getFirst().purchaseCount()).isEqualTo(1);
		assertThat(dashboard.promotions().getFirst().cost()).isEqualTo(200);
		assertThat(
						new PromotionAnalytics()
								.createDashboard(List.of(first), List.of(), NOW)
								.promotions()
								.getFirst()
								.cost())
				.isEqualTo(200);
	}

	private Purchase createPurchase(
			String id, List<Promotion> promotions, List<Selection> selections) {
		var input = createCart(List.of(createLine(id, 2, 1000)), selections.toArray(Selection[]::new));
		return new Purchase(
				"owner",
				new Order(
						id,
						id + "-quote",
						input,
						promotions,
						new PricingEngine().evaluateCartPrice(input, promotions, NOW),
						NOW));
	}
}
