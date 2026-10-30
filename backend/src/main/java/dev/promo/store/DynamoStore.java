package dev.promo.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Persists versioned documents with conditional DynamoDB transactions. */
@Repository
public class DynamoStore implements Store {
	private final DynamoDbClient client;
	private final String table;

	public DynamoStore(
			DynamoDbClient client,
			@Value("${sandbox.table}") String table,
			@Value("${sandbox.initialize:false}") boolean initialize) {
		this.client = client;
		this.table = table;
		if (initialize) {
			initializeTable();
		}
	}

	private void initializeTable() {
		try {
			client.describeTable(r -> r.tableName(table));
		} catch (ResourceNotFoundException missing) {
			try {
				client.createTable(
						r ->
								r.tableName(table)
										.billingMode(BillingMode.PAY_PER_REQUEST)
										.attributeDefinitions(
												AttributeDefinition.builder()
														.attributeName("pk")
														.attributeType(ScalarAttributeType.S)
														.build(),
												AttributeDefinition.builder()
														.attributeName("sk")
														.attributeType(ScalarAttributeType.S)
														.build())
										.keySchema(
												KeySchemaElement.builder()
														.attributeName("pk")
														.keyType(KeyType.HASH)
														.build(),
												KeySchemaElement.builder()
														.attributeName("sk")
														.keyType(KeyType.RANGE)
														.build()));
			} catch (ResourceInUseException concurrent) {
				// Another instance already created the table.
			}
			client.waiter().waitUntilTableExists(r -> r.tableName(table));
		}
	}

	@Override
	public Optional<Document> findDocument(String partition, String id) {
		var item =
				client
						.getItem(
								r -> r.tableName(table).key(buildDocumentKey(partition, id)).consistentRead(true))
						.item();
		return item.isEmpty() ? Optional.empty() : Optional.of(deserializeStoredDocument(item));
	}

	@Override
	public List<Document> listDocuments(String partition) {
		var result = new ArrayList<Document>();
		for (var page :
				client.queryPaginator(
						r ->
								r.tableName(table)
										.consistentRead(true)
										.keyConditionExpression("pk = :pk")
										.expressionAttributeValues(Map.of(":pk", createStringAttribute(partition))))) {
			page.items().forEach(item -> result.add(deserializeStoredDocument(item)));
		}
		return result;
	}

	@Override
	public void executeTransaction(List<Write> writes, List<Check> checks) {
		var actions = new ArrayList<TransactWriteItem>();
		for (var write : writes) {
			if (write.json() == null) {
				var deletion =
						Delete.builder()
								.tableName(table)
								.key(buildDocumentKey(write.partition(), write.id()))
								.conditionExpression(buildVersionCondition(write.expectedVersion()));
				if (write.expectedVersion() > 0) {
					deletion.expressionAttributeValues(
							Map.of(":v", createNumberAttribute(write.expectedVersion())));
				}
				actions.add(TransactWriteItem.builder().delete(deletion.build()).build());
				continue;
			}
			var item = new HashMap<>(buildDocumentKey(write.partition(), write.id()));
			item.put("v", createNumberAttribute(write.expectedVersion() + 1));
			item.put("payload", createStringAttribute(write.json()));
			var builder =
					Put.builder()
							.tableName(table)
							.item(item)
							.conditionExpression(buildVersionCondition(write.expectedVersion()));
			if (write.expectedVersion() > 0) {
				builder.expressionAttributeValues(
						Map.of(":v", createNumberAttribute(write.expectedVersion())));
			}
			actions.add(TransactWriteItem.builder().put(builder.build()).build());
		}
		for (var check : checks) {
			var builder =
					ConditionCheck.builder()
							.tableName(table)
							.key(buildDocumentKey(check.partition(), check.id()))
							.conditionExpression(buildVersionCondition(check.expectedVersion()));
			if (check.expectedVersion() > 0) {
				builder.expressionAttributeValues(
						Map.of(":v", createNumberAttribute(check.expectedVersion())));
			}
			actions.add(TransactWriteItem.builder().conditionCheck(builder.build()).build());
		}
		try {
			client.transactWriteItems(r -> r.transactItems(actions));
		} catch (TransactionCanceledException exception) {
			if (exception.cancellationReasons().stream()
					.anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()))) {
				throw new Conflict("Data changed; reload and retry");
			}
			throw exception;
		}
	}

	private String buildVersionCondition(long version) {
		return version == 0 ? "attribute_not_exists(pk)" : "v = :v";
	}

	private Map<String, AttributeValue> buildDocumentKey(String partition, String id) {
		return Map.of("pk", createStringAttribute(partition), "sk", createStringAttribute(id));
	}

	private AttributeValue createStringAttribute(String value) {
		return AttributeValue.builder().s(value).build();
	}

	private AttributeValue createNumberAttribute(long value) {
		return AttributeValue.builder().n(Long.toString(value)).build();
	}

	private Document deserializeStoredDocument(Map<String, AttributeValue> item) {
		return new Document(
				item.get("sk").s(), Long.parseLong(item.get("v").n()), item.get("payload").s());
	}
}
