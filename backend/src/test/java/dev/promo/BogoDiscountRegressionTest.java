package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static dev.promo.Fixtures.createPromotion;
import static dev.promo.Fixtures.withItemTarget;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promo.domain.Model.Allocation;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Selection;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.PricingEngine;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BogoDiscountRegressionTest {

	@ParameterizedTest
	@CsvSource({"0,0,a", "100,100,a", "499,499,a", "500,500,a", "501,500,b"})
	void earlierDiscountDeterminesWhichUnitMayBeFree(
			long remainingA, long bogoSaving, String freeLine) {
		var engine = new PricingEngine();
		var discount =
				withItemTarget(
						createPromotion("discount-a", Scope.ONE_ITEM, Strategy.FLAT, 1000 - remainingA), "a");
		var bogo = createPromotion("bogo", Scope.ITEMS, Strategy.BOGO, 0);
		for (boolean reversePair : List.of(false, true)) {
			var pair = reversePair ? List.of("b:0000", "a:0000") : List.of("a:0000", "b:0000");
			var cart =
					createCart(
							List.of(createLine("a", 1, 1000), createLine("b", 1, 500)),
							new Selection("discount-a", "a", List.of()),
							new Selection("bogo", null, List.of(pair)));
			var result = engine.evaluateCartPrice(cart, List.of(discount, bogo), NOW);
			var bogoAllocations =
					result.steps().stream()
							.filter(step -> step.promotionId().equals("bogo"))
							.flatMap(step -> step.allocations().stream())
							.toList();
			assertThat(bogoAllocations.stream().mapToLong(Allocation::saving).sum())
					.isEqualTo(bogoSaving);
			assertThat(bogoAllocations).allMatch(allocation -> allocation.lineId().equals(freeLine));
			assertThat(result.total()).isEqualTo(1000 + Math.max(remainingA, 500));
		}
	}
}
