package dev.promo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.promo.store.Conflict;
import dev.promo.store.DynamoStore;
import dev.promo.store.Store;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@EnabledIfEnvironmentVariable(named = "DYNAMO_TEST_ENDPOINT", matches = ".+")
class DynamoStoreTest {
	@Test
	void purchasesPersistAtomicallyAndCannotBeRepeatedAfterServiceRestart() {
		try (var client = createLocalDynamoClient()) {
			String table = "purchase-test-" + UUID.randomUUID();
			var store = new DynamoStore(client, table, true);
			try {
				var engine = new dev.promo.domain.PricingEngine();
				var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
				var service =
						new dev.promo.api.SandboxService(
								store,
								mapper,
								engine,
								new dev.promo.domain.Optimizer(engine),
								java.time.Clock.fixed(Fixtures.NOW, java.time.ZoneOffset.UTC));
				var cart =
						service.createCart(
								"owner",
								Fixtures.createCart(List.of(Fixtures.createLine("persisted-item", 2, 1000))));
				var quote = service.createQuote("owner", cart.cart().id(), 1);
				var order = service.submitOrder("owner", quote.id(), "persisted-order-key-123");
				assertThat(store.findDocument("user#owner#carts", cart.cart().id())).isEmpty();
				assertThat(store.listDocuments("purchases")).hasSize(1);
				assertThat(store.scanLegacyOrders()).hasSize(1);
				var restarted =
						new dev.promo.api.SandboxService(
								new DynamoStore(client, table, true),
								mapper,
								engine,
								new dev.promo.domain.Optimizer(engine),
								java.time.Clock.fixed(Fixtures.NOW, java.time.ZoneOffset.UTC));
				assertThat(restarted.listOrders("owner")).containsExactly(order);
				assertThat(restarted.submitOrder("owner", quote.id(), order.id())).isEqualTo(order);
				assertThatThrownBy(
								() -> restarted.submitOrder("owner", quote.id(), "another-purchase-key-123"))
						.isInstanceOf(Conflict.class);
				assertThat(restarted.getPromotionDashboard().purchaseCount()).isEqualTo(1);
			} finally {
				client.deleteTable(request -> request.tableName(table));
			}
		}
	}

	private DynamoDbClient createLocalDynamoClient() {
		return DynamoDbClient.builder()
				.region(Region.US_EAST_1)
				.endpointOverride(URI.create(System.getenv("DYNAMO_TEST_ENDPOINT")))
				.credentialsProvider(
						StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
				.build();
	}

	@Test
	void conditionalTransactionsAndPersistence() {
		try (var client = createLocalDynamoClient()) {
			String table = "test-" + UUID.randomUUID();
			var store = new DynamoStore(client, table, true);
			try {
				store.executeTransaction(
						List.of(new Store.Write("owner", "cart", 0, "{\"version\":1}")), List.of());
				assertThat(store.findDocument("owner", "cart").orElseThrow().version()).isEqualTo(1);
				assertThat(store.findDocument("other", "cart")).isEmpty();
				assertThatThrownBy(
								() ->
										store.executeTransaction(
												List.of(
														new Store.Write("owner", "order", 0, "{}"),
														new Store.Write("owner", "cart", 0, "{}")),
												List.of()))
						.isInstanceOf(Conflict.class);
				assertThat(store.findDocument("owner", "order")).isEmpty();
				store.executeTransaction(
						List.of(
								new Store.Write("owner", "order", 0, "{}"),
								new Store.Write("owner", "cart", 1, "{\"version\":2}")),
						List.of());
				var restarted = new DynamoStore(client, table, true);
				assertThat(restarted.listDocuments("owner")).hasSize(2);
				assertThat(restarted.findDocument("owner", "cart").orElseThrow().version()).isEqualTo(2);
			} finally {
				client.deleteTable(r -> r.tableName(table));
			}
		}
	}
}
