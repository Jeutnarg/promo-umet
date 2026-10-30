package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promo.api.SandboxService;
import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.Order;
import dev.promo.store.Conflict;
import dev.promo.store.DynamoStore;
import dev.promo.store.Store;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseLifecycleTest {
	@Autowired private SandboxService service;
	@Autowired private ObjectMapper mapper;
	@Autowired private MockMvc mvc;
	@MockitoBean private DynamoStore store;
	private final MemoryStore documents = new MemoryStore();

	@BeforeEach
	void configureRepositoryDouble() {
		org.mockito.Mockito.when(
						store.findDocument(
								org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(call -> documents.findDocument(call.getArgument(0), call.getArgument(1)));
		org.mockito.Mockito.when(store.listDocuments(org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(call -> documents.listDocuments(call.getArgument(0)));
		org.mockito.Mockito.when(store.scanLegacyOrders())
				.thenAnswer(call -> documents.scanLegacyOrders());
		org.mockito.Mockito.doAnswer(
						call -> {
							documents.executeTransaction(call.getArgument(0), call.getArgument(1));
							return null;
						})
				.when(store)
				.executeTransaction(
						org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void purchaseRemovesCartRetainsSnapshotAndRejectsEveryOtherQuoteOrKey() {
		var original =
				service.createCart("alice", createCart(List.of(createLine("purchase-item", 2, 1000))));
		var firstQuote = service.createQuote("alice", original.cart().id(), 1);
		var secondQuote = service.createQuote("alice", original.cart().id(), 1);
		var order = service.submitOrder("alice", firstQuote.id(), "purchase-first-key-123");
		assertThat(service.listCarts("alice")).isEmpty();
		assertThat(service.listCartSummaries("alice")).isEmpty();
		assertThatThrownBy(() -> service.getCart("alice", original.cart().id()))
				.isInstanceOf(NoSuchElementException.class);
		assertThat(service.listOrders("alice")).containsExactly(order);
		assertThat(service.submitOrder("alice", firstQuote.id(), order.id())).isEqualTo(order);
		assertThatThrownBy(
						() -> service.submitOrder("alice", firstQuote.id(), "purchase-other-key-123"))
				.isInstanceOf(Conflict.class);
		assertThatThrownBy(
						() -> service.submitOrder("alice", secondQuote.id(), "purchase-second-quote-123"))
				.isInstanceOf(Conflict.class);
		assertThat(service.getPromotionDashboard().purchaseCount()).isEqualTo(1);
		var clone = service.clonePurchasedCart("alice", order.id());
		assertThat(clone.cart().id()).isNotEqualTo(original.cart().id());
		assertThat(clone.cart().lines()).isEqualTo(original.cart().lines());
		assertThatThrownBy(() -> service.clonePurchasedCart("bob", order.id()))
				.isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void concurrentDifferentKeysCanCreateOnlyOnePurchase() throws Exception {
		var cart = service.createCart("alice", createCart(List.of(createLine("racing-item", 1, 1000))));
		var firstQuote = service.createQuote("alice", cart.cart().id(), 1);
		var secondQuote = service.createQuote("alice", cart.cart().id(), 1);
		var barrier = new CyclicBarrier(2);
		org.mockito.Mockito.doAnswer(
						call -> {
							List<Store.Write> writes = call.getArgument(0);
							if (writes.stream().anyMatch(write -> write.partition().equals("purchases"))) {
								barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
							}
							documents.executeTransaction(writes, call.getArgument(1));
							return null;
						})
				.when(store)
				.executeTransaction(
						org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
		var successes = new AtomicInteger();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var futures =
					executor.invokeAll(
							List.of(
									() -> {
										try {
											service.submitOrder("alice", firstQuote.id(), "concurrent-key-one-123");
											successes.incrementAndGet();
										} catch (Conflict expectedConflict) {
											assertThat(expectedConflict).hasMessageContaining("changed");
										}
										return true;
									},
									() -> {
										try {
											service.submitOrder("alice", secondQuote.id(), "concurrent-key-two-123");
											successes.incrementAndGet();
										} catch (Conflict expectedConflict) {
											assertThat(expectedConflict).hasMessageContaining("changed");
										}
										return true;
									}));
			for (var result : futures) {
				result.get();
			}
		}
		assertThat(successes.get()).isEqualTo(1);
		assertThat(service.listOrders("alice")).hasSize(1);
		assertThat(documents.listDocuments("purchases")).hasSize(1);
		assertThat(service.listCarts("alice")).isEmpty();
	}

	@Test
	void failedPurchaseTransactionLeavesCartAndDoesNotRecordOrCountAPurchase() {
		var cart =
				service.createCart("alice", createCart(List.of(createLine("failure-item", 1, 1000))));
		var quote = service.createQuote("alice", cart.cart().id(), 1);
		org.mockito.Mockito.doThrow(new Conflict("Injected transaction conflict"))
				.when(store)
				.executeTransaction(
						org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
		assertThatThrownBy(() -> service.submitOrder("alice", quote.id(), "failed-purchase-key-123"))
				.isInstanceOf(Conflict.class);
		assertThat(service.listCarts("alice")).hasSize(1);
		assertThat(service.listOrders("alice")).isEmpty();
		assertThat(service.getPromotionDashboard().purchaseCount()).isZero();
	}

	@Test
	void dashboardEndpointRequiresAnAdministrator() throws Exception {
		mvc.perform(get("/api/v1/admin/dashboard/promotions")).andExpect(status().isUnauthorized());
		mvc.perform(
						get("/api/v1/admin/dashboard/promotions")
								.with(jwt().jwt(token -> token.subject("customer"))))
				.andExpect(status().isForbidden());
		var administrator =
				jwt()
						.authorities(
								new org.springframework.security.core.authority.SimpleGrantedAuthority(
										"ROLE_admin"));
		mvc.perform(get("/api/v1/admin/dashboard/promotions").with(administrator))
				.andExpect(status().isOk());
	}

	@Test
	void legacyMigrationPreservesOrdersDeletesPurchasedCartsAndRunsOnlyOnce() throws Exception {
		var original =
				service.createCart("legacy-owner", createCart(List.of(createLine("legacy-item", 2, 1000))));
		var order =
				new Order(
						"legacy-order", "legacy-quote", original.cart(), List.of(), original.evaluation(), NOW);
		var completed =
				new Cart(original.cart().id(), 2, original.cart().lines(), 1000, List.of(), true);
		documents.executeTransaction(
				List.of(
						new Store.Write(
								"user#legacy-owner#orders", order.id(), 0, mapper.writeValueAsString(order)),
						new Store.Write(
								"user#legacy-owner#carts",
								completed.id(),
								1,
								mapper.writeValueAsString(completed))),
				List.of());
		service.migrateLegacyPurchases();
		service.migrateLegacyPurchases();
		assertThat(service.listOrders("legacy-owner")).containsExactly(order);
		assertThat(documents.findDocument("user#legacy-owner#carts", completed.id())).isEmpty();
		assertThat(service.getPromotionDashboard().purchaseCount()).isEqualTo(1);
		org.mockito.Mockito.verify(store, org.mockito.Mockito.times(1)).scanLegacyOrders();
	}
}
