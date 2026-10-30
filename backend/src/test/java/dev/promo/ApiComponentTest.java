package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promo.api.SandboxService;
import dev.promo.domain.Model.Details;
import dev.promo.domain.Model.Exclusivity;
import dev.promo.domain.Model.Item;
import dev.promo.domain.Model.Promotion;
import dev.promo.domain.Model.Quote;
import dev.promo.domain.Model.Scope;
import dev.promo.domain.Model.Strategy;
import dev.promo.domain.Model.TestCase;
import dev.promo.domain.Model.TestSet;
import dev.promo.store.DynamoStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class ApiComponentTest {
	@Test
	void persistsAndRunsBogoTripleFixturesThroughVersionedApi() throws Exception {
		// Given an administrator saves a triple offer using its public JSON strategy name.
		var promotion =
				new Promotion(
						"triple-api",
						0,
						"Triple offer",
						Scope.ITEMS,
						Exclusivity.STACKABLE,
						Strategy.BOGO_TRIPLE,
						new Details(null, null, null, null),
						List.of(),
						List.of(),
						null,
						true,
						1);
		mvc.perform(
						withAuthenticatedAdmin(put("/api/v1/admin/promotions/triple-api"))
								.content(serializeJson(promotion)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.strategy").value("bogotriple"));
		var cart =
				Fixtures.createCart(
						List.of(createLine("sponsor", 1, 1000), createLine("addons", 2, 200)),
						new dev.promo.domain.Model.Selection(promotion.id()));
		// When the customer evaluates the saved offer and the administrator runs an imported fixture.
		mvc.perform(withAuthenticatedAdmin(post("/api/v1/carts")).content(serializeJson(cart)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.evaluation.total").value(2000));
		var set =
				new TestSet(
						"triple-fixtures",
						0,
						1,
						"Triple fixture",
						List.of(
								new TestCase(
										"Two free add-ons", cart, List.of(promotion), "manual", NOW, 2000, 2)));
		mvc.perform(
						withAuthenticatedAdmin(put("/api/v1/admin/testsets/triple-fixtures"))
								.content(serializeJson(set)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cases[0].promotions[0].strategy").value("bogotriple"));
		mvc.perform(withAuthenticatedAdmin(post("/api/v1/admin/tests/run")).content(serializeJson(set)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].passed").value(true));
		// Then persisted/exportable fixtures preserve the typed strategy.
		mvc.perform(withAuthenticatedAdmin(get("/api/v1/admin/testsets")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].cases[0].promotions[0].strategy").value("bogotriple"));
	}

	@Test
	void deletesOnlyOwnedCartAndCreatesEmptyReplacement() throws Exception {
		var original =
				service.createCart("alice", createCart(List.of(createLine("delete-me", 2, 1000))));
		String route = "/api/v1/carts/" + original.cart().id();
		mvc.perform(
						delete(route)
								.with(jwt().jwt(token -> token.subject("bob")))
								.contentType("application/json")
								.content("{\"version\":1}"))
				.andExpect(status().isNotFound());
		mvc.perform(withAuthenticatedAdmin(delete(route)).content("{\"version\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cart.lines").isEmpty());
		mvc.perform(withAuthenticatedAdmin(get(route))).andExpect(status().isNotFound());
		assertThat(service.listCarts("alice")).hasSize(1);
		assertThat(service.listCarts("alice").getFirst().savedAt()).isNotNull();
	}

	@Test
	void promotionDescriptionSurvivesSavingAndReading() {
		var p =
				new Promotion(
						"description",
						0,
						"An offer",
						Scope.ITEMS,
						Exclusivity.STACKABLE,
						Strategy.FLAT,
						new Details(100L, null, null, null),
						List.of(),
						List.of(),
						NOW.minusSeconds(10),
						true,
						1,
						"Save a dollar across your merchandise.");
		service.savePromotion("admin", p);
		assertThat(service.listPromotions().getFirst().description()).isEqualTo(p.description());
	}

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired SandboxService service;
	@MockitoBean DynamoStore store;
	private final MemoryStore memory = new MemoryStore();

	@BeforeEach
	void configureStoreDouble() {
		when(store.findDocument(anyString(), anyString()))
				.thenAnswer(
						invocation ->
								memory.findDocument(invocation.getArgument(0), invocation.getArgument(1)));
		when(store.listDocuments(anyString()))
				.thenAnswer(invocation -> memory.listDocuments(invocation.getArgument(0)));
		doAnswer(
						invocation -> {
							memory.executeTransaction(invocation.getArgument(0), invocation.getArgument(1));
							return null;
						})
				.when(store)
				.executeTransaction(anyList(), anyList());
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
			withAuthenticatedAdmin(
					org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
		return request
				.with(
						jwt()
								.jwt(token -> token.subject("alice"))
								.authorities(new SimpleGrantedAuthority("ROLE_admin")))
				.contentType("application/json");
	}

	private String serializeJson(Object object) throws Exception {
		return mapper.writeValueAsString(object);
	}

	@Test
	void exportsOpenApi() throws Exception {
		var schema =
				mvc.perform(get("/v3/api-docs"))
						.andExpect(status().isOk())
						.andReturn()
						.getResponse()
						.getContentAsString();
		Files.createDirectories(Path.of("target"));
		Files.writeString(Path.of("target/openapi.json"), schema);
	}

	@Test
	void completeCustomerFlowAndIsolation() throws Exception {
		// Given a customer with a reusable item and a populated cart.
		mvc.perform(
						withAuthenticatedAdmin(put("/api/v1/items/item-a"))
								.content(
										serializeJson(
												new SandboxService.ItemView(new Item("item-a", "Tea", 1000, "drinks"), 0))))
				.andExpect(status().isOk());
		var response =
				mvc.perform(
								withAuthenticatedAdmin(post("/api/v1/carts"))
										.content(serializeJson(createCart(List.of(createLine("line-a", 2, 1000))))))
						.andExpect(status().isOk())
						.andReturn();
		var view =
				mapper.readValue(
						response.getResponse().getContentAsString(), SandboxService.CartView.class);
		mvc.perform(
						get("/api/v1/carts/" + view.cart().id()).with(jwt().jwt(token -> token.subject("bob"))))
				.andExpect(status().isNotFound());
		// When the customer confirms and submits twice with the same key.
		var quoteResponse =
				mvc.perform(
								withAuthenticatedAdmin(post("/api/v1/carts/" + view.cart().id() + "/quotes"))
										.content("{\"version\":1}"))
						.andExpect(status().isOk())
						.andReturn();
		var quote = mapper.readValue(quoteResponse.getResponse().getContentAsString(), Quote.class);
		String key = "idempotency-key-12345";
		for (int i = 0; i < 2; i++) {
			mvc.perform(
							withAuthenticatedAdmin(post("/api/v1/orders"))
									.header("Idempotency-Key", key)
									.content("{\"quoteId\":\"" + quote.id() + "\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.evaluation.total").value(3000));
		}
		// Then exactly one order exists and cloning creates an editable cart.
		assertThat(service.listOrders("alice")).hasSize(1);
		mvc.perform(withAuthenticatedAdmin(get("/api/v1/carts/" + view.cart().id())))
				.andExpect(status().isNotFound());
		mvc.perform(withAuthenticatedAdmin(post("/api/v1/orders/" + key + "/clone")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cart.completed").value(false));
	}

	@Test
	void administratorAndValidationBoundaries() throws Exception {
		mvc.perform(get("/api/v1/items")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/admin/testsets").with(jwt())).andExpect(status().isForbidden());
		mvc.perform(withAuthenticatedAdmin(post("/api/v1/carts")).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(withAuthenticatedAdmin(post("/api/v1/carts")).content("{"))
				.andExpect(status().isBadRequest());
		mvc.perform(withAuthenticatedAdmin(post("/api/v1/carts")).content(" ".repeat(262145)))
				.andExpect(status().isPayloadTooLarge());
	}

	@ParameterizedTest
	@ValueSource(
			strings = {
				"items",
				"item-save",
				"carts",
				"cart-summaries",
				"create",
				"view",
				"save",
				"clone",
				"evaluate",
				"optimize",
				"quote",
				"submit",
				"orders",
				"order-clone",
				"promotion-dashboard",
				"promotions",
				"promotion-save",
				"testsets",
				"testset-save",
				"audit"
			})
	void everyStorageFailurePrefixIsExercised(String endpoint) throws Exception {
		// Given unique endpoint fixtures and a successful reference execution.
		var request = createEndpointFixture(endpoint);
		memory.failOnAccess(-1);
		mvc.perform(request).andExpect(status().isOk());
		int accesses = memory.getAccessCount();
		// When each downstream access fails after the preceding accesses succeed.
		for (int fail = 1; fail <= accesses; fail++) {
			memory.resetDocuments();
			request = createEndpointFixture(endpoint);
			memory.failOnAccess(fail);
			mvc.perform(request).andExpect(status().isServiceUnavailable());
			// Then no downstream access follows the failed access.
			assertThat(memory.getAccessCount()).isEqualTo(fail);
		}
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
			createEndpointFixture(String endpoint) throws Exception {
		String id = "unique-" + endpoint;
		var item = new Item(id, id, 1234, "test");
		service.saveItem("alice", item, 0);
		var view = service.createCart("alice", createCart(List.of(createLine(id, 2, 1234))));
		String base = "/api/v1/carts/" + view.cart().id();
		return withAuthenticatedAdmin(
				switch (endpoint) {
					case "items" -> get("/api/v1/items");
					case "item-save" ->
							put("/api/v1/items/" + id)
									.content(serializeJson(new SandboxService.ItemView(item, 1)));
					case "carts" -> get("/api/v1/carts");
					case "cart-summaries" -> get("/api/v1/carts/summaries");
					case "create" ->
							post("/api/v1/carts")
									.content(serializeJson(createCart(List.of(createLine("new-" + id, 1, 123)))));
					case "view" -> get(base);
					case "save" -> put(base).content(serializeJson(view.cart()));
					case "clone" -> post(base + "/clone");
					case "evaluate" -> post(base + "/evaluate");
					case "optimize" ->
							post(base + "/optimize")
									.content(
											serializeJson(
													Map.of(
															"version",
															1,
															"promotionRevision",
															view.evaluation().promotionRevision(),
															"mode",
															"suggest")));
					case "quote" -> post(base + "/quotes").content("{\"version\":1}");
					case "submit" ->
							post("/api/v1/orders")
									.header("Idempotency-Key", "unique-submission-123")
									.content(
											serializeJson(
													Map.of(
															"quoteId", service.createQuote("alice", view.cart().id(), 1).id())));
					case "orders" -> get("/api/v1/orders");
					case "order-clone" ->
							post(
									"/api/v1/orders/"
											+ service
													.submitOrder(
															"alice",
															service.createQuote("alice", view.cart().id(), 1).id(),
															"cloned-purchase-key-123")
													.id()
											+ "/clone");
					case "promotion-dashboard" -> get("/api/v1/admin/dashboard/promotions");
					case "promotions" -> get("/api/v1/promotions");
					case "promotion-save" ->
							put("/api/v1/admin/promotions/" + id)
									.content(
											serializeJson(
													new Promotion(
															id,
															0,
															id,
															Scope.ITEMS,
															Exclusivity.STACKABLE,
															Strategy.FLAT,
															new Details(100L, null, null, null),
															List.of(),
															List.of(),
															null,
															true,
															1)));
					case "testsets" -> get("/api/v1/admin/testsets");
					case "testset-save" ->
							put("/api/v1/admin/testsets/" + id)
									.content(serializeJson(new TestSet(id, 0, 1, id, List.of())));
					case "audit" -> get("/api/v1/admin/audit");
					default -> throw new IllegalArgumentException(endpoint);
				});
	}

	@Test
	void testRunnerIsDeterministicAndDoesNotAccessStorage() throws Exception {
		var test =
				new TestCase(
						"fixed-time",
						createCart(List.of(createLine("test", 1, 1000))),
						List.of(),
						"manual",
						NOW,
						2000,
						0);
		mvc.perform(
						withAuthenticatedAdmin(post("/api/v1/admin/tests/run"))
								.content(serializeJson(new TestSet("set", 0, 1, "test", List.of(test)))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].passed").value(true));
		verifyNoInteractions(store);
	}

	@Test
	void staleVersionsAndChangedPromotionsRequireConfirmation() throws Exception {
		var view = service.createCart("alice", createCart(List.of(createLine("stale", 1, 1000))));
		var quote = service.createQuote("alice", view.cart().id(), 1);
		service.savePromotion(
				"admin",
				new Promotion(
						"new",
						0,
						"New",
						Scope.ITEMS,
						Exclusivity.STACKABLE,
						Strategy.FLAT,
						new Details(100L, null, null, null),
						List.of(),
						List.of(),
						null,
						true,
						1));
		mvc.perform(
						withAuthenticatedAdmin(post("/api/v1/orders"))
								.header("Idempotency-Key", "changed-promotion-123")
								.content(serializeJson(Map.of("quoteId", quote.id()))))
				.andExpect(status().isConflict());
		service.saveCart("alice", view.cart().id(), view.cart());
		mvc.perform(
						withAuthenticatedAdmin(put("/api/v1/carts/" + view.cart().id()))
								.content(serializeJson(view.cart())))
				.andExpect(status().isConflict());
	}
}
