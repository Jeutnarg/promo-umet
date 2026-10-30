package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promo.api.SandboxService;
import dev.promo.domain.Model.Cart;
import dev.promo.domain.Model.OptimizationResult;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import dev.promo.store.Conflict;
import dev.promo.store.Store;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxServiceTest {
	private final Store store = mock(Store.class);
	private final PricingEngine engine = mock(PricingEngine.class);
	private final Optimizer optimizer = mock(Optimizer.class);
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final SandboxService service =
			new SandboxService(store, mapper, engine, optimizer, Clock.fixed(NOW, ZoneOffset.UTC), 10);

	@Test
	void creationPassesInputsToEngineAndPersistsItsCart() throws Exception {
		// The application delegates pricing and returns the evaluator response unchanged.
		var input = createCart(List.of(createLine("unit", 2, 123)));
		var expected = new PricingEngine().evaluateCartPrice(input, List.of(), NOW);
		when(store.findDocument("catalog", "revision")).thenReturn(Optional.empty());
		when(store.listDocuments("promotions")).thenReturn(List.of());
		when(engine.evaluateCartPrice(any(), eq(List.of()), eq(NOW))).thenReturn(expected);
		var view = service.createCart("owner", input);
		var cartCaptor = ArgumentCaptor.forClass(Cart.class);
		verify(engine).evaluateCartPrice(cartCaptor.capture(), eq(List.of()), eq(NOW));
		assertThat(cartCaptor.getValue().lines()).isEqualTo(input.lines());
		assertThat(cartCaptor.getValue().version()).isEqualTo(1);
		assertThat(view.evaluation()).isSameAs(expected);
		verify(store)
				.executeTransaction(
						argThat(
								writes ->
										writes.size() == 1
												&& writes.getFirst().partition().equals("user#owner#carts")
												&& writes.getFirst().json().contains(view.cart().id())),
						eq(List.of(new Store.Check("catalog", "revision", 0))));
	}

	@Test
	void completedCartNeverInvokesOptimizer() throws Exception {
		// A completed cart rejects optimization before calling the domain service.
		var cart = new Cart("completed", 2, List.of(createLine("a", 1, 100)), 1000, List.of(), true);
		when(store.findDocument("user#owner#carts", "completed"))
				.thenReturn(
						Optional.of(new Store.Document("completed", 2, mapper.writeValueAsString(cart))));
		assertThatThrownBy(() -> service.optimizeCart("owner", "completed", 2, "revision", "suggest"))
				.isInstanceOf(Conflict.class);
		verifyNoInteractions(optimizer, engine);
		verify(store, never()).executeTransaction(anyList(), anyList());
	}

	@Test
	void optimizerArgumentsAndResultArePassedToPersistence() throws Exception {
		// The optimizer receives the saved snapshot and its selections become the next cart version.
		var cart = createCart(List.of(createLine("a", 1, 1000)));
		var evaluation = new PricingEngine().evaluateCartPrice(cart, List.of(), NOW);
		var result = new OptimizationResult(List.of(), evaluation, true, true, 1, "suggest");
		when(store.findDocument("user#owner#carts", "cart"))
				.thenReturn(Optional.of(new Store.Document("cart", 1, mapper.writeValueAsString(cart))));
		when(store.findDocument("catalog", "revision")).thenReturn(Optional.empty());
		when(store.listDocuments("promotions")).thenReturn(List.of());
		when(engine.calculatePromotionRevision(List.of())).thenReturn("revision");
		when(optimizer.findOptimalPromotions(cart, List.of(), NOW, "suggest", Duration.ofSeconds(2)))
				.thenReturn(result);
		when(engine.evaluateCartPrice(any(), eq(List.of()), eq(NOW))).thenReturn(evaluation);
		var saved = service.optimizeCart("owner", "cart", 1, "revision", "suggest");
		verify(optimizer).findOptimalPromotions(cart, List.of(), NOW, "suggest", Duration.ofSeconds(2));
		assertThat(saved.cart().version()).isEqualTo(2);
		assertThat(saved.result().evaluation()).isSameAs(evaluation);
		verify(store)
				.executeTransaction(
						argThat(writes -> writes.getFirst().expectedVersion() == 1),
						eq(List.of(new Store.Check("catalog", "revision", 0))));
	}
}
