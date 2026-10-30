package dev.promo;

import static dev.promo.Fixtures.NOW;
import static dev.promo.Fixtures.createCart;
import static dev.promo.Fixtures.createLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promo.api.ApiError;
import dev.promo.api.SandboxService;
import dev.promo.domain.Model.OptimizationResult;
import dev.promo.domain.Model.TestCase;
import dev.promo.domain.Model.TestSet;
import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import dev.promo.store.Store;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CalculationConcurrencyTest {
	private final Store store = mock(Store.class);
	private final PricingEngine engine = new PricingEngine();
	private final Optimizer optimizer = mock(Optimizer.class);
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final dev.promo.domain.Model.Cart cart = createCart(List.of(createLine("a", 1, 1000)));
	private final TestSet testSet =
			new TestSet(
					"concurrency",
					1,
					1,
					"Concurrency fixture",
					List.of(new TestCase("Simple search", cart, List.of(), "suggest", NOW, 2000, 0)));
	private final OptimizationResult result =
			new OptimizationResult(
					List.of(), engine.evaluateCartPrice(cart, List.of(), NOW), true, true, 1, "suggest");
	private final ApplicationContextRunner contextRunner =
			new ApplicationContextRunner()
					.withUserConfiguration(SandboxService.class)
					.withBean(Store.class, () -> store)
					.withBean(ObjectMapper.class, () -> mapper)
					.withBean(PricingEngine.class, () -> engine)
					.withBean(Optimizer.class, () -> optimizer)
					.withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));

	@ParameterizedTest
	@CsvSource({"default,10", "1,1", "2,2"})
	void enforcesConfiguredSharedCapacityAndReusesReleasedPermits(String configured, int capacity)
			throws Exception {
		var entered = new CountDownLatch(capacity);
		var finish = new CountDownLatch(1);
		when(optimizer.findOptimalPromotions(any(), anyList(), any(), anyString(), any()))
				.thenAnswer(
						invocation -> {
							entered.countDown();
							if (!finish.await(10, TimeUnit.SECONDS))
								throw new IllegalStateException("Worker was not released");
							return result;
						});
		stubOwnedCart();
		var runner =
				configured.equals("default")
						? contextRunner
						: contextRunner.withPropertyValues("sandbox.calculation-concurrency=" + configured);
		runner.run(
				context -> {
					assertThat(context).hasNotFailed();
					var service = context.getBean(SandboxService.class);
					try (var workers = Executors.newFixedThreadPool(capacity)) {
						var running = new ArrayList<Future<?>>();
						try {
							// Given every configured slot is occupied by an administrator test run.
							for (int index = 0; index < capacity; index++)
								running.add(workers.submit(() -> service.runTestSet(testSet)));
							assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
							// Then both another test run and a customer search reject admission immediately.
							assertThatThrownBy(() -> service.runTestSet(testSet))
									.isInstanceOf(ApiError.Busy.class);
							assertThatThrownBy(() -> optimizeCart(service)).isInstanceOf(ApiError.Busy.class);
						} finally {
							finish.countDown();
						}
						for (var request : running) request.get(5, TimeUnit.SECONDS);
						// When the earlier work completes, its permits are available to both paths again.
						assertThat(service.runTestSet(testSet)).allMatch(test -> test.passed());
						assertThat(optimizeCart(service).result().evaluation().total()).isEqualTo(2000);
					}
				});
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1})
	void rejectsNonpositiveConcurrencyAtStartup(int configured) {
		contextRunner
				.withPropertyValues("sandbox.calculation-concurrency=" + configured)
				.run(
						context -> {
							assertThat(context).hasFailed();
							assertThat(context.getStartupFailure())
									.hasRootCauseInstanceOf(IllegalArgumentException.class)
									.hasStackTraceContaining("Calculation concurrency must be at least 1");
						});
	}

	@ParameterizedTest
	@ValueSource(strings = {"tests", "optimization"})
	void releasesCapacityAfterDownstreamCalculationFailure(String path) throws Exception {
		stubOwnedCart();
		when(optimizer.findOptimalPromotions(any(), anyList(), any(), anyString(), any()))
				.thenThrow(new IllegalStateException("Calculation failed"))
				.thenReturn(result);
		contextRunner
				.withPropertyValues("sandbox.calculation-concurrency=1")
				.run(
						context -> {
							var service = context.getBean(SandboxService.class);
							assertThatThrownBy(
											() -> {
												if (path.equals("tests")) service.runTestSet(testSet);
												else optimizeCart(service);
											})
									.isInstanceOf(IllegalStateException.class);
							assertThat(service.runTestSet(testSet)).allMatch(test -> test.passed());
						});
	}

	private void stubOwnedCart() throws Exception {
		when(store.findDocument("user#owner#carts", cart.id()))
				.thenReturn(
						Optional.of(
								new Store.Document(cart.id(), cart.version(), mapper.writeValueAsString(cart))));
		when(store.findDocument("catalog", "revision")).thenReturn(Optional.empty());
		when(store.listDocuments("promotions")).thenReturn(List.of());
	}

	private SandboxService.OptimizedCart optimizeCart(SandboxService service) {
		return service.optimizeCart(
				"owner",
				cart.id(),
				cart.version(),
				engine.calculatePromotionRevision(List.of()),
				"suggest");
	}
}
