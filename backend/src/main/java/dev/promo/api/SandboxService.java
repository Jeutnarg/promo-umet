package dev.promo.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promo.domain.Model.Audit;
import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Evaluation;
import dev.promo.domain.Model.Item;
import dev.promo.domain.Model.OptimizationResult;
import dev.promo.domain.Model.Order;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.PromotionDashboard;
import dev.promo.domain.Model.Purchase;
import dev.promo.domain.Model.Quote;
import dev.promo.domain.Model.TestResult;
import dev.promo.domain.Model.TestSet;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import dev.promo.domain.PromotionAnalytics;
import dev.promo.domain.Rules;
import dev.promo.store.Conflict;
import dev.promo.store.Store;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Coordinates owner-scoped persistence, pricing, quotes, and order submission. */
@Service
public class SandboxService {
	private static final org.slf4j.Logger LOG =
			org.slf4j.LoggerFactory.getLogger(SandboxService.class);
	private final Store store;
	private final ObjectMapper mapper;
	private final PricingEngine engine;
	private final Optimizer optimizer;
	private final Clock clock;
	private final Semaphore calculations;

	/** Combines a saved cart with its current price explanation. */
	public record CartView(Cart cart, Evaluation evaluation) {}

	/** Combines a committed cart with optimization proof information. */
	public record OptimizedCart(Cart cart, OptimizationResult result) {}

	/** Combines a catalog item with its optimistic storage version. */
	public record ItemView(Item item, long version) {}

	/** Summarizes a saved cart using current server-calculated pricing. */
	public record CartSummary(
			String id, long total, int totalQuantity, Instant savedAt, boolean completed) {}

	private record Catalog(long version, List<Promotion> promotions) {}

	public SandboxService(
			Store store,
			ObjectMapper mapper,
			PricingEngine engine,
			Optimizer optimizer,
			Clock clock,
			@Value("${sandbox.calculation-concurrency:10}") int calculationConcurrency) {
		Rules.validateCondition(
				calculationConcurrency >= 1, "Calculation concurrency must be at least 1");
		this.store = store;
		this.mapper = mapper;
		this.engine = engine;
		this.optimizer = optimizer;
		this.clock = clock;
		calculations = new Semaphore(calculationConcurrency);
	}

	public List<ItemView> listItems(String owner) {
		return store.listDocuments(buildOwnerPartition(owner, "items")).stream()
				.map(doc -> new ItemView(deserializeDocument(doc, Item.class), doc.version()))
				.toList();
	}

	public ItemView saveItem(String owner, Item item, long version) {
		Rules.validateItem(item);
		persistDocument(buildOwnerPartition(owner, "items"), item.id(), version, item);
		return new ItemView(item, version + 1);
	}

	public CartView deleteCart(String owner, String id, long version) {
		var current = getRequiredDocument(buildOwnerPartition(owner, "carts"), id, Cart.class);
		validateEditableCart(current, version);
		var replacement = new Cart(UUID.randomUUID().toString(), 1, List.of(), 1000, List.of(), false);
		var catalog = loadPromotionCatalog();
		var evaluation = engine.evaluateCartPrice(replacement, catalog.promotions(), clock.instant());
		store.executeTransaction(
				List.of(
						new Store.Write(buildOwnerPartition(owner, "carts"), id, version, null),
						createDocumentWrite(
								buildOwnerPartition(owner, "carts"), replacement.id(), 0, replacement)),
				List.of(createCatalogVersionCheck(catalog)));
		return new CartView(replacement, evaluation);
	}

	public List<Cart> listCarts(String owner) {
		return listDocuments(buildOwnerPartition(owner, "carts"), Cart.class).stream()
				.filter(cart -> !cart.completed())
				.toList();
	}

	public List<CartSummary> listCartSummaries(String owner) {
		var catalog = loadPromotionCatalog();
		var now = clock.instant();
		return listCarts(owner).stream()
				.sorted(
						Comparator.comparing(Cart::savedAt, Comparator.nullsLast(Comparator.reverseOrder()))
								.thenComparing(Cart::id))
				.map(
						cart ->
								new CartSummary(
										cart.id(),
										engine.evaluateCartPrice(cart, catalog.promotions(), now).total(),
										cart.lines().stream().mapToInt(line -> line.quantity()).sum(),
										cart.savedAt(),
										cart.completed()))
				.toList();
	}

	public CartView createCart(String owner, Cart input) {
		Rules.validateCart(input);
		var cart =
				new Cart(
						UUID.randomUUID().toString(),
						1,
						input.lines(),
						input.shipping(),
						input.selections(),
						false);
		var catalog = loadPromotionCatalog();
		var evaluation = engine.evaluateCartPrice(cart, catalog.promotions(), clock.instant());
		store.executeTransaction(
				List.of(createDocumentWrite(buildOwnerPartition(owner, "carts"), cart.id(), 0, cart)),
				List.of(createCatalogVersionCheck(catalog)));
		return new CartView(cart, evaluation);
	}

	public CartView getCart(String owner, String id) {
		var cart = getRequiredDocument(buildOwnerPartition(owner, "carts"), id, Cart.class);
		var catalog = loadPromotionCatalog();
		return new CartView(
				cart, engine.evaluateCartPrice(cart, catalog.promotions(), clock.instant()));
	}

	public CartView saveCart(String owner, String id, Cart input) {
		Rules.validateCart(input);
		Rules.validateCondition(id.equals(input.id()), "Cart ID mismatch");
		var current = getRequiredDocument(buildOwnerPartition(owner, "carts"), id, Cart.class);
		validateEditableCart(current, input.version());
		var catalog = loadPromotionCatalog();
		var cart =
				new Cart(
						id, input.version() + 1, input.lines(), input.shipping(), input.selections(), false);
		var evaluation = engine.evaluateCartPrice(cart, catalog.promotions(), clock.instant());
		store.executeTransaction(
				List.of(
						createDocumentWrite(buildOwnerPartition(owner, "carts"), id, input.version(), cart)),
				List.of(createCatalogVersionCheck(catalog)));
		return new CartView(cart, evaluation);
	}

	public CartView cloneCart(String owner, String id) {
		var original = getRequiredDocument(buildOwnerPartition(owner, "carts"), id, Cart.class);
		return createCart(
				owner,
				new Cart(null, 0, original.lines(), original.shipping(), original.selections(), false));
	}

	public OptimizedCart optimizeCart(
			String owner, String id, long version, String revision, String mode) {
		var current = getRequiredDocument(buildOwnerPartition(owner, "carts"), id, Cart.class);
		validateEditableCart(current, version);
		var catalog = loadPromotionCatalog();
		if (!engine.calculatePromotionRevision(catalog.promotions()).equals(revision)) {
			throw new Conflict("Promotions changed; refresh and retry");
		}
		var result = runBoundedOptimization(current, catalog.promotions(), clock.instant(), mode);
		var updated =
				new Cart(id, version + 1, current.lines(), current.shipping(), result.selections(), false);
		var refreshed = engine.evaluateCartPrice(updated, catalog.promotions(), clock.instant());
		if (!refreshed.rejected().isEmpty() || refreshed.total() != result.evaluation().total()) {
			throw new Conflict("Promotion expired during optimization; retry");
		}
		store.executeTransaction(
				List.of(createDocumentWrite(buildOwnerPartition(owner, "carts"), id, version, updated)),
				List.of(createCatalogVersionCheck(catalog)));
		return new OptimizedCart(
				updated,
				new OptimizationResult(
						result.selections(),
						refreshed,
						result.optimalityProven(),
						result.coverageProven(),
						result.candidates(),
						mode));
	}

	public Quote createQuote(String owner, String id, long version) {
		var cart = getRequiredDocument(buildOwnerPartition(owner, "carts"), id, Cart.class);
		validateEditableCart(cart, version);
		Rules.validateCondition(!cart.lines().isEmpty(), "Cannot check out an empty cart");
		var catalog = loadPromotionCatalog();
		var now = clock.instant();
		var evaluation = engine.evaluateCartPrice(cart, catalog.promotions(), now);
		if (!evaluation.rejected().isEmpty()) {
			throw new Conflict("Remove unavailable promotions before confirming");
		}
		var quote =
				new Quote(
						UUID.randomUUID().toString(),
						cart,
						catalog.promotions(),
						evaluation,
						now.plusSeconds(300));
		store.executeTransaction(
				List.of(createDocumentWrite(buildOwnerPartition(owner, "quotes"), quote.id(), 0, quote)),
				List.of(
						createCatalogVersionCheck(catalog),
						new Store.Check(buildOwnerPartition(owner, "carts"), id, version)));
		return quote;
	}

	public Order submitOrder(String owner, String quoteId, String key) {
		Rules.validateCondition(
				key != null && key.matches("[A-Za-z0-9-]{16,100}"),
				"A 16–100 character Idempotency-Key is required");
		var existing = store.findDocument(buildOwnerPartition(owner, "orders"), key);
		if (existing.isPresent()) {
			return getMatchingOrder(existing.get(), quoteId);
		}
		var quote = getRequiredDocument(buildOwnerPartition(owner, "quotes"), quoteId, Quote.class);
		if (store.findDocument("purchases", quote.cart().id()).isPresent()) {
			throw new Conflict("This cart has already been purchased");
		}
		if (!clock.instant().isBefore(quote.expiresAt())) {
			throw new Conflict("Quote expired; confirm again");
		}
		var cart =
				getRequiredDocument(buildOwnerPartition(owner, "carts"), quote.cart().id(), Cart.class);
		validateEditableCart(cart, quote.cart().version());
		var catalog = loadPromotionCatalog();
		var evaluation = engine.evaluateCartPrice(cart, catalog.promotions(), clock.instant());
		if (!evaluation.promotionRevision().equals(quote.evaluation().promotionRevision())
				|| !evaluation.steps().equals(quote.evaluation().steps())
				|| evaluation.total() != quote.evaluation().total()
				|| !evaluation.rejected().isEmpty()) {
			throw new Conflict("Pricing changed; confirm again");
		}
		var order = new Order(key, quoteId, cart, catalog.promotions(), evaluation, clock.instant());
		try {
			store.executeTransaction(
					List.of(
							createDocumentWrite(buildOwnerPartition(owner, "orders"), key, 0, order),
							createDocumentWrite("purchases", cart.id(), 0, new Purchase(owner, order)),
							new Store.Write(
									buildOwnerPartition(owner, "carts"), cart.id(), cart.version(), null)),
					List.of(createCatalogVersionCheck(catalog)));
		} catch (Conflict conflict) {
			var retry = store.findDocument(buildOwnerPartition(owner, "orders"), key);
			if (retry.isPresent()) {
				return getMatchingOrder(retry.get(), quoteId);
			}
			throw conflict;
		}
		LOG.atInfo()
				.addKeyValue(
						"cart_ref", dev.promo.observability.RequestDiagnostics.createReference(cart.id()))
				.addKeyValue("units", cart.lines().stream().mapToInt(line -> line.quantity()).sum())
				.addKeyValue("applied_promotions", evaluation.steps().size())
				.log("purchase_recorded");
		return order;
	}

	public CartView clonePurchasedCart(String owner, String orderId) {
		var order = getRequiredDocument(buildOwnerPartition(owner, "orders"), orderId, Order.class);
		var snapshot = order.cart();
		return createCart(
				owner,
				new Cart(null, 0, snapshot.lines(), snapshot.shipping(), snapshot.selections(), false));
	}

	public PromotionDashboard getPromotionDashboard() {
		return new PromotionAnalytics()
				.createDashboard(
						listDocuments("purchases", Purchase.class), listPromotions(), clock.instant());
	}

	public void migrateLegacyPurchases() {
		for (int attempt = 0; attempt < 5; attempt++) {
			if (store.findDocument("migrations", "purchase-ledger-v1").isPresent()) {
				return;
			}
			try {
				for (var legacy : store.scanLegacyOrders()) {
					migrateLegacyPurchase(legacy);
				}
				store.executeTransaction(
						List.of(
								createDocumentWrite(
										"migrations", "purchase-ledger-v1", 0, Map.of("completedAt", clock.instant()))),
						List.of());
				return;
			} catch (Conflict concurrentMigration) {
				if (attempt == 4) {
					throw concurrentMigration;
				}
			}
		}
	}

	private void migrateLegacyPurchase(Store.LegacyOrderDocument legacy) {
		var order = deserializeDocument(legacy.document(), Order.class);
		var existing = store.findDocument("purchases", order.cart().id());
		if (existing.isPresent()) {
			var purchase = deserializeDocument(existing.get(), Purchase.class);
			if (!purchase.owner().equals(legacy.owner()) || !purchase.order().id().equals(order.id())) {
				throw new IllegalStateException("Multiple legacy purchases exist for a cart");
			}
		} else {
			store.executeTransaction(
					List.of(
							createDocumentWrite(
									"purchases", order.cart().id(), 0, new Purchase(legacy.owner(), order))),
					List.of());
		}
		var completedCart =
				store.findDocument(buildOwnerPartition(legacy.owner(), "carts"), order.cart().id());
		if (completedCart.isPresent()) {
			store.executeTransaction(
					List.of(
							new Store.Write(
									buildOwnerPartition(legacy.owner(), "carts"),
									order.cart().id(),
									completedCart.get().version(),
									null)),
					List.of());
		}
	}

	private Order getMatchingOrder(Store.Document document, String quoteId) {
		var order = deserializeDocument(document, Order.class);
		if (!order.quoteId().equals(quoteId)) {
			throw new Conflict("Idempotency key was used for another quote");
		}
		LOG.atInfo()
				.addKeyValue(
						"cart_ref",
						dev.promo.observability.RequestDiagnostics.createReference(order.cart().id()))
				.log("purchase_retry_reused");
		return order;
	}

	public List<Order> listOrders(String owner) {
		return listDocuments(buildOwnerPartition(owner, "orders"), Order.class);
	}

	public List<Promotion> listPromotions() {
		return loadPromotionCatalog().promotions();
	}

	public Promotion savePromotion(String actor, Promotion input) {
		Rules.validatePromotion(input);
		var catalog = loadPromotionCatalog();
		Rules.validateCondition(
				input.version() > 0 || catalog.promotions().size() < 50,
				"Maximum 50 promotions; reuse or disable an existing definition");
		var promotion =
				new Promotion(
						input.id(),
						input.version() + 1,
						input.name(),
						input.scope(),
						input.exclusivity(),
						input.strategy(),
						input.details(),
						input.itemIds(),
						input.categories(),
						input.expiresAt(),
						input.enabled(),
						input.schemaVersion(),
						input.description());
		store.executeTransaction(
				List.of(
						createDocumentWrite("promotions", input.id(), input.version(), promotion),
						createDocumentWrite(
								"catalog", "revision", catalog.version(), Map.of("updatedAt", clock.instant())),
						createAuditWrite(actor, "promotion.save", input.id())),
				List.of());
		return promotion;
	}

	public List<TestSet> listTestSets() {
		return listDocuments("testsets", TestSet.class);
	}

	public TestSet saveTestSet(String actor, TestSet input) {
		Rules.validateTestSet(input);
		Rules.validateText(input.id(), "Test set ID");
		var set =
				new TestSet(
						input.id(), input.version() + 1, input.schemaVersion(), input.name(), input.cases());
		store.executeTransaction(
				List.of(
						createDocumentWrite("testsets", input.id(), input.version(), set),
						createAuditWrite(actor, "testset.save", input.id())),
				List.of());
		return set;
	}

	public List<TestResult> runTestSet(TestSet set) {
		Rules.validateTestSet(set);
		Rules.validateCondition(
				set.cases().stream().filter(test -> !test.mode().equals("manual")).count() <= 10,
				"At most 10 optimizer cases per run");
		if (!calculations.tryAcquire()) {
			throw new ApiError.Busy();
		}
		try {
			return set.cases().stream()
					.map(
							test -> {
								try {
									var result =
											test.mode().equals("manual")
													? null
													: optimizer.findOptimalPromotions(
															test.cart(),
															test.promotions(),
															test.now(),
															test.mode(),
															Duration.ofSeconds(2));
									var evaluation =
											result == null
													? engine.evaluateCartPrice(test.cart(), test.promotions(), test.now())
													: result.evaluation();
									boolean passed =
											evaluation.total() == test.expectedTotal()
													&& (test.expectedCoveredUnits() == null
															|| test.expectedCoveredUnits() == evaluation.coveredUnits().size());
									return new TestResult(
											test.name(),
											passed,
											evaluation,
											null,
											result == null ? null : result.optimalityProven());
								} catch (IllegalArgumentException exception) {
									return new TestResult(test.name(), false, null, exception.getMessage(), null);
								}
							})
					.toList();
		} finally {
			calculations.release();
		}
	}

	public List<Audit> listAuditEvents() {
		return listDocuments("audit", Audit.class);
	}

	private OptimizationResult runBoundedOptimization(
			Cart cart, List<Promotion> promotions, Instant now, String mode) {
		if (!calculations.tryAcquire()) {
			throw new ApiError.Busy();
		}
		try {
			return optimizer.findOptimalPromotions(cart, promotions, now, mode, Duration.ofSeconds(2));
		} finally {
			calculations.release();
		}
	}

	private Catalog loadPromotionCatalog() {
		for (int attempt = 0; attempt < 3; attempt++) {
			long before = getCatalogVersion();
			var promotions = listDocuments("promotions", Promotion.class);
			long after = getCatalogVersion();
			if (before == after) {
				return new Catalog(after, promotions);
			}
		}
		throw new Conflict("Promotions are being edited; retry shortly");
	}

	private long getCatalogVersion() {
		return store.findDocument("catalog", "revision").map(Store.Document::version).orElse(0L);
	}

	private Store.Check createCatalogVersionCheck(Catalog catalog) {
		return new Store.Check("catalog", "revision", catalog.version());
	}

	private Store.Write createAuditWrite(String actor, String action, String id) {
		return createDocumentWrite(
				"audit",
				clock.instant() + "-" + UUID.randomUUID(),
				0,
				new Audit(actor, action, id, clock.instant()));
	}

	private void validateEditableCart(Cart cart, long version) {
		if (cart.completed() || cart.version() != version) {
			throw new Conflict("Cart is completed or changed; reload it");
		}
	}

	private String buildOwnerPartition(String owner, String resource) {
		return "user#" + owner + "#" + resource;
	}

	private <T> List<T> listDocuments(String partition, Class<T> type) {
		return store.listDocuments(partition).stream()
				.map(doc -> deserializeDocument(doc, type))
				.toList();
	}

	private <T> T getRequiredDocument(String partition, String id, Class<T> type) {
		return deserializeDocument(store.findDocument(partition, id).orElseThrow(), type);
	}

	private <T> T deserializeDocument(Store.Document doc, Class<T> type) {
		try {
			return mapper.readValue(doc.json(), type);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Invalid stored document", exception);
		}
	}

	private Store.Write createDocumentWrite(String partition, String id, long version, Object data) {
		Rules.validateCondition(version >= 0, "Version cannot be negative");
		try {
			String json = mapper.writeValueAsString(data);
			Rules.validateCondition(
					json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 350_000,
					"Stored document exceeds size limit");
			return new Store.Write(partition, id, version, json);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Cannot encode document", exception);
		}
	}

	private void persistDocument(String partition, String id, long version, Object data) {
		store.executeTransaction(List.of(createDocumentWrite(partition, id, version, data)), List.of());
	}
}
