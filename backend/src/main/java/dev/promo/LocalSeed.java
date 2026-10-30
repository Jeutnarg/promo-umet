package dev.promo;

import dev.promo.api.SandboxService;
import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Details;
import dev.promo.domain.Model.Exclusivity;
import dev.promo.domain.Model.Item;
import dev.promo.domain.Model.Line;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Selection;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.Model.TestCase;
import dev.promo.domain.Model.TestSet;
import dev.promo.store.Conflict;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Initializes local development data without replacing existing records. */
@Component
@Profile("local & !test")
public class LocalSeed implements ApplicationRunner {
	private final SandboxService service;

	public LocalSeed(SandboxService service) {
		this.service = service;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (service.listPromotions().isEmpty()) {
			try {
				service.savePromotion(
						"local-bootstrap",
						new Promotion(
								"bogobongo",
								0,
								"BogoBongo",
								Scope.ITEM,
								Exclusivity.STACKABLE,
								Strategy.BOGO,
								new Details(null, null, null, null),
								List.of("sample-one"),
								List.of(),
								null,
								true,
								1));
				service.savePromotion(
						"local-bootstrap",
						new Promotion(
								"moarsav",
								0,
								"MOARSAV · 20% off",
								Scope.ITEM,
								Exclusivity.STACKABLE,
								Strategy.PERCENT,
								new Details(null, 2000, null, null),
								List.of("sample-two"),
								List.of(),
								null,
								true,
								1));
				service.savePromotion(
						"local-bootstrap",
						new Promotion(
								"shipit",
								0,
								"SHIPIT · free shipping over $50",
								Scope.ORDER,
								Exclusivity.STACKABLE,
								Strategy.SHIPPING_GOAL,
								new Details(null, null, 5000L, null),
								List.of(),
								List.of(),
								null,
								true,
								1));
			} catch (Conflict concurrentSeed) {
				// Another instance already initialized this record.
			}
		}
		for (String user :
				List.of("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")) {
			if (service.listItems(user).isEmpty()) {
				try {
					service.saveItem(user, new Item("sample-one", "Everyday tote", 1000, "Accessories"), 0);
					service.saveItem(user, new Item("sample-two", "Studio mug", 1500, "Home"), 0);
				} catch (Conflict concurrentSeed) {
					// Another instance already initialized this record.
				}
			}
		}
		if (service.listTestSets().isEmpty()) {
			var cart =
					new Cart(
							"sketch",
							1,
							List.of(
									new Line("one", "sample-one", "Everyday tote", 4, 1000, "Accessories"),
									new Line("two", "sample-two", "Studio mug", 2, 1500, "Home")),
							1000,
							List.of(
									new Selection("bogobongo", "one", List.of()),
									new Selection("moarsav", "two", List.of())),
							false);
			try {
				service.saveTestSet(
						"local-bootstrap",
						new TestSet(
								"sketch-example",
								0,
								1,
								"The $54 sketch",
								List.of(
										new TestCase(
												"Four totes, two mugs",
												cart,
												service.listPromotions(),
												"manual",
												Instant.parse("2026-01-01T00:00:00Z"),
												5400,
												4))));
			} catch (Conflict concurrentSeed) {
				// Another instance already initialized this record.
			}
		}
	}
}
