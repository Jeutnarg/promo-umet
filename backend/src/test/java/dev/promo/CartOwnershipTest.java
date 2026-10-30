package dev.promo;

import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promo.api.SandboxService;
import dev.promo.domain.Model.Cart;
import dev.promo.store.DynamoStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartOwnershipTest {
	@Test
	void cartSummariesAreOwnerScopedAndUseCalculatedTotalsAndQuantities() throws Exception {
		var original =
				service.createCart("alice", createCart(List.of(createLine("summary-item", 3, 1500))));
		var summaries = service.listCartSummaries("alice");
		assertThat(summaries).hasSize(1);
		assertThat(summaries.getFirst().id()).isEqualTo(original.cart().id());
		assertThat(summaries.getFirst().total()).isEqualTo(5500);
		assertThat(summaries.getFirst().totalQuantity()).isEqualTo(3);
		var latest =
				service.createCart("alice", createCart(List.of(createLine("newest-item", 1, 100))));
		var olderSnapshot =
				new Cart(
						original.cart().id(),
						2,
						original.cart().lines(),
						original.cart().shipping(),
						original.cart().selections(),
						false,
						latest.cart().savedAt().minusSeconds(1));
		documents.executeTransaction(
				List.of(
						new dev.promo.store.Store.Write(
								"user#alice#carts",
								original.cart().id(),
								1,
								mapper.writeValueAsString(olderSnapshot))),
				List.of());
		assertThat(service.listCartSummaries("alice"))
				.extracting(SandboxService.CartSummary::id)
				.containsExactly(latest.cart().id(), original.cart().id());
		mvc.perform(get("/api/v1/carts/summaries").with(jwt().jwt(token -> token.subject("bob"))))
				.andExpect(status().isOk())
				.andExpect(
						org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
	}

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private SandboxService service;
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
		org.mockito.Mockito.doAnswer(
						call -> {
							documents.executeTransaction(call.getArgument(0), call.getArgument(1));
							return null;
						})
				.when(store)
				.executeTransaction(
						org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
	}

	@ParameterizedTest
	@CsvSource({
		"read,customer",
		"update,customer",
		"delete,customer",
		"clone,customer",
		"evaluate,customer",
		"optimize,customer",
		"quote,customer",
		"read,admin",
		"update,admin",
		"delete,admin",
		"clone,admin",
		"evaluate,admin",
		"optimize,admin",
		"quote,admin"
	})
	void foreignCartOperationsReturnNotFoundAndLeaveOwnerDataUnchanged(String operation, String role)
			throws Exception {
		// Given Alice's cart and Bob's authenticated identity, including the administrator role.
		var original =
				service.createCart("alice", createCart(List.of(createLine("private-item", 2, 1500))));
		String path = "/api/v1/carts/" + original.cart().id();
		MockHttpServletRequestBuilder request =
				switch (operation) {
					case "read" -> get(path);
					case "update" -> put(path).content(mapper.writeValueAsString(original.cart()));
					case "delete" -> delete(path).content("{\"version\":1}");
					case "clone" -> post(path + "/clone");
					case "evaluate" -> post(path + "/evaluate");
					case "optimize" ->
							post(path + "/optimize")
									.content(
											"{\"version\":1,\"mode\":\"suggest\",\"promotionRevision\":\"ignored\"}");
					case "quote" -> post(path + "/quotes").content("{\"version\":1}");
					default -> throw new IllegalArgumentException(operation);
				};
		// When Bob supplies Alice's resource identifier, the response matches a missing resource.
		mvc.perform(
						request
								.contentType("application/json")
								.with(
										jwt()
												.jwt(token -> token.subject("bob"))
												.authorities(new SimpleGrantedAuthority("ROLE_" + role))))
				.andExpect(status().isNotFound());
		// Then Alice's cart is unchanged and Bob gained no cart or order.
		assertThat(service.getCart("alice", original.cart().id()).cart()).isEqualTo(original.cart());
		assertThat(service.listCarts("bob")).isEmpty();
		assertThat(service.listOrders("bob")).isEmpty();
	}

	@Test
	void foreignQuotesAndReusedOrderKeysCannotRevealOrSubmitAnotherUsersOrder() throws Exception {
		var cart = service.createCart("alice", createCart(List.of(createLine("order-item", 1, 1000))));
		var quote = service.createQuote("alice", cart.cart().id(), 1);
		String key = "shared-idempotency-key-123";
		for (boolean ownerSubmitted : List.of(false, true)) {
			if (ownerSubmitted) {
				service.submitOrder("alice", quote.id(), key);
			}
			mvc.perform(
							post("/api/v1/orders")
									.contentType("application/json")
									.header("Idempotency-Key", key)
									.content(mapper.writeValueAsString(java.util.Map.of("quoteId", quote.id())))
									.with(jwt().jwt(token -> token.subject("bob"))))
					.andExpect(status().isNotFound());
		}
		assertThat(service.listOrders("alice")).hasSize(1);
		assertThat(service.listOrders("bob")).isEmpty();
	}

	@Test
	void forgedCartBodyCannotRedirectAnOwnedCartUpdate() throws Exception {
		var alice = service.createCart("alice", createCart(List.of(createLine("alice-item", 1, 1000))));
		var bob = service.createCart("bob", createCart(List.of(createLine("bob-item", 1, 2000))));
		var forged = new Cart(alice.cart().id(), bob.cart().version(), List.of(), 0, List.of(), false);
		mvc.perform(
						put("/api/v1/carts/" + bob.cart().id())
								.contentType("application/json")
								.content(mapper.writeValueAsString(forged))
								.with(jwt().jwt(token -> token.subject("bob"))))
				.andExpect(status().isBadRequest());
		assertThat(service.getCart("alice", alice.cart().id()).cart()).isEqualTo(alice.cart());
		assertThat(service.getCart("bob", bob.cart().id()).cart()).isEqualTo(bob.cart());
	}
}
