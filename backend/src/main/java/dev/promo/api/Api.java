package dev.promo.api;

import static dev.promo.api.SandboxService.CartView;
import static dev.promo.api.SandboxService.ItemView;
import static dev.promo.api.SandboxService.OptimizedCart;

import dev.promo.domain.Model.Audit;
import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Order;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Quote;
import dev.promo.domain.Model.TestResult;
import dev.promo.domain.Model.TestSet;
import dev.promo.domain.Rules;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes customer resources scoped to the authenticated subject and administrator operations. */
@RestController
@RequestMapping("/api/v1")
public class Api {
	private final SandboxService service;

	public Api(SandboxService service) {
		this.service = service;
	}

	/** Identifies the saved pricing snapshot and requested optimization objective. */
	public record OptimizeRequest(long version, String promotionRevision, String mode) {}

	/** Identifies the cart version being confirmed or deleted. */
	public record QuoteRequest(long version) {}

	/** Identifies the confirmation quote being submitted. */
	public record SubmitRequest(String quoteId) {}

	@GetMapping("/items")
	public List<ItemView> listItems(@AuthenticationPrincipal Jwt jwt) {
		return service.listItems(jwt.getSubject());
	}

	@PutMapping("/items/{id}")
	public ItemView saveItem(
			@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody ItemView body) {
		Rules.validateItem(body.item());
		Rules.validateCondition(id.equals(body.item().id()), "Item ID mismatch");
		return service.saveItem(jwt.getSubject(), body.item(), body.version());
	}

	@GetMapping("/carts")
	public List<Cart> listCarts(@AuthenticationPrincipal Jwt jwt) {
		return service.listCarts(jwt.getSubject());
	}

	@GetMapping("/carts/summaries")
	public List<SandboxService.CartSummary> listCartSummaries(@AuthenticationPrincipal Jwt jwt) {
		return service.listCartSummaries(jwt.getSubject());
	}

	@PostMapping("/carts")
	public CartView createCart(@AuthenticationPrincipal Jwt jwt, @RequestBody Cart cart) {
		return service.createCart(jwt.getSubject(), cart);
	}

	@DeleteMapping("/carts/{id}")
	public CartView deleteCart(
			@AuthenticationPrincipal Jwt jwt,
			@PathVariable String id,
			@RequestBody QuoteRequest request) {
		return service.deleteCart(jwt.getSubject(), id, request.version());
	}

	@GetMapping("/carts/{id}")
	public CartView getCart(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return service.getCart(jwt.getSubject(), id);
	}

	@PutMapping("/carts/{id}")
	public CartView updateCart(
			@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Cart cart) {
		return service.saveCart(jwt.getSubject(), id, cart);
	}

	@PostMapping("/carts/{id}/clone")
	public CartView cloneCart(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return service.cloneCart(jwt.getSubject(), id);
	}

	@PostMapping("/carts/{id}/evaluate")
	public CartView evaluateCart(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return service.getCart(jwt.getSubject(), id);
	}

	@PostMapping("/carts/{id}/optimize")
	public OptimizedCart optimizeCart(
			@AuthenticationPrincipal Jwt jwt,
			@PathVariable String id,
			@RequestBody OptimizeRequest body) {
		return service.optimizeCart(
				jwt.getSubject(), id, body.version(), body.promotionRevision(), body.mode());
	}

	@PostMapping("/carts/{id}/quotes")
	public Quote createQuote(
			@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody QuoteRequest body) {
		return service.createQuote(jwt.getSubject(), id, body.version());
	}

	@PostMapping("/orders")
	public Order submitOrder(
			@AuthenticationPrincipal Jwt jwt,
			@RequestBody SubmitRequest body,
			@RequestHeader("Idempotency-Key") String key) {
		return service.submitOrder(jwt.getSubject(), body.quoteId(), key);
	}

	@GetMapping("/orders")
	public List<Order> listOrders(@AuthenticationPrincipal Jwt jwt) {
		return service.listOrders(jwt.getSubject());
	}

	@GetMapping("/promotions")
	public List<Promotion> listPromotions() {
		return service.listPromotions();
	}

	@PutMapping("/admin/promotions/{id}")
	public Promotion savePromotion(
			@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Promotion promotion) {
		Rules.validateCondition(id.equals(promotion.id()), "Promotion ID mismatch");
		return service.savePromotion(jwt.getSubject(), promotion);
	}

	@GetMapping("/admin/testsets")
	public List<TestSet> listTestSets() {
		return service.listTestSets();
	}

	@PutMapping("/admin/testsets/{id}")
	public TestSet saveTestSet(
			@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody TestSet set) {
		Rules.validateCondition(id.equals(set.id()), "Test set ID mismatch");
		return service.saveTestSet(jwt.getSubject(), set);
	}

	@PostMapping("/admin/tests/run")
	public List<TestResult> runTestSet(@RequestBody TestSet set) {
		return service.runTestSet(set);
	}

	@GetMapping("/admin/audit")
	public List<Audit> listAuditEvents() {
		return service.listAuditEvents();
	}
}
