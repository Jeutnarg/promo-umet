package dev.promo;

import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Details;
import dev.promo.domain.Model.Exclusivity;
import dev.promo.domain.Model.Line;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Selection;
import dev.promo.domain.Model.Strategy;
import java.time.Instant;
import java.util.List;

/** Builds reusable deterministic pricing fixtures. */
public final class Fixtures {
	public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private Fixtures() {}

	public static Line createLine(String id, int quantity, long price) {
		return new Line(id, id, id, quantity, price, "everyday");
	}

	public static Cart createCart(List<Line> lines, Selection... selections) {
		return new Cart("cart", 1, lines, 1000, List.of(selections), false);
	}

	public static Promotion createPromotion(String id, Scope scope, Strategy strategy, long amount) {
		return new Promotion(
				id,
				1,
				id,
				scope,
				Exclusivity.STACKABLE,
				strategy,
				new Details(amount, 2000, 0L, 1),
				List.of(),
				List.of(),
				null,
				true,
				1);
	}

	public static Promotion withExclusivity(Promotion p, Exclusivity e) {
		return new Promotion(
				p.id(),
				p.version(),
				p.name(),
				p.scope(),
				e,
				p.strategy(),
				p.details(),
				p.itemIds(),
				p.categories(),
				p.expiresAt(),
				p.enabled(),
				1);
	}

	public static Promotion withItemTarget(Promotion p, String item) {
		return new Promotion(
				p.id(),
				p.version(),
				p.name(),
				p.scope(),
				p.exclusivity(),
				p.strategy(),
				p.details(),
				List.of(item),
				p.categories(),
				p.expiresAt(),
				p.enabled(),
				1);
	}
}
