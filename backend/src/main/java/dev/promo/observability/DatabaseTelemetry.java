package dev.promo.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Measures actual synchronous SDK calls, including every page and all retry attempts. */
public final class DatabaseTelemetry implements ExecutionInterceptor {
	private static final Logger LOG = LoggerFactory.getLogger(DatabaseTelemetry.class);
	private static final ExecutionAttribute<Long> STARTED =
			new ExecutionAttribute<>("sandbox.database.started");
	private static final ExecutionAttribute<Integer> ATTEMPTS =
			new ExecutionAttribute<>("sandbox.database.attempts");
	private static final Set<String> FAMILIES =
			Set.of(
					"items",
					"carts",
					"orders",
					"quotes",
					"promotions",
					"catalog",
					"purchases",
					"migrations",
					"testsets",
					"audit");
	private static final Set<String> OPERATIONS =
			Set.of("GetItem", "Query", "Scan", "TransactWriteItems", "DescribeTable", "CreateTable");
	private final MeterRegistry metrics;

	public DatabaseTelemetry(MeterRegistry metrics) {
		this.metrics = metrics;
	}

	@Override
	public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
		attributes.putAttribute(STARTED, System.nanoTime());
		attributes.putAttribute(ATTEMPTS, 0);
	}

	@Override
	public void beforeTransmission(
			Context.BeforeTransmission context, ExecutionAttributes attributes) {
		Integer attempts = attributes.getAttribute(ATTEMPTS);
		attributes.putAttribute(ATTEMPTS, attempts == null ? 1 : attempts + 1);
	}

	@Override
	public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
		recordCall(context.request(), context.response(), null, attributes);
	}

	@Override
	public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
		recordCall(context.request(), null, context.exception(), attributes);
	}

	private void recordCall(
			SdkRequest request, SdkResponse response, Throwable failure, ExecutionAttributes attributes) {
		Long started = attributes.getAttribute(STARTED);
		long elapsed = started == null ? 0 : System.nanoTime() - started;
		Integer transmissions = attributes.getAttribute(ATTEMPTS);
		int attempts = transmissions == null ? 0 : transmissions;
		String operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
		operation = OPERATIONS.contains(operation == null ? "" : operation) ? operation : "Other";
		String family = identifyResourceFamily(request);
		boolean conflict =
				failure instanceof TransactionCanceledException canceled
						&& canceled.cancellationReasons().stream()
								.anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()));
		String outcome = failure == null ? "success" : conflict ? "conflict" : "error";
		metrics
				.timer(
						"sandbox.database.duration",
						"operation",
						operation,
						"resource",
						family,
						"outcome",
						outcome)
				.record(elapsed, TimeUnit.NANOSECONDS);
		metrics
				.counter("sandbox.database.attempts", "operation", operation, "resource", family)
				.increment(attempts);
		var diagnostics = RequestDiagnostics.currentRequest();
		if (diagnostics != null) {
			diagnostics.recordDatabaseCall(elapsed, attempts, failure != null);
		}
		var log = failure == null ? LOG.atInfo() : conflict ? LOG.atWarn() : LOG.atError();
		log.addKeyValue("db_operation", operation)
				.addKeyValue("db_resource", family)
				.addKeyValue("outcome", outcome)
				.addKeyValue("duration_ms", elapsed / 1_000_000.0)
				.addKeyValue("attempts", attempts);
		if (request instanceof TransactWriteItemsRequest transaction) {
			log.addKeyValue("transaction_items", transaction.transactItems().size());
		}
		if (response instanceof DynamoDbResponse dynamo && dynamo.responseMetadata() != null) {
			log.addKeyValue("aws_request_id", dynamo.responseMetadata().requestId());
		}
		if (response instanceof GetItemResponse item) {
			log.addKeyValue("returned_items", item.hasItem() ? 1 : 0);
			recordCapacity(log, item.consumedCapacity());
		}
		if (response instanceof QueryResponse query) {
			log.addKeyValue("returned_items", query.count())
					.addKeyValue("evaluated_items", query.scannedCount());
			recordCapacity(log, query.consumedCapacity());
		}
		if (response instanceof ScanResponse scan) {
			log.addKeyValue("returned_items", scan.count())
					.addKeyValue("evaluated_items", scan.scannedCount());
			recordCapacity(log, scan.consumedCapacity());
		}
		if (response instanceof TransactWriteItemsResponse transaction
				&& !transaction.consumedCapacity().isEmpty()) {
			log.addKeyValue(
					"consumed_capacity_units",
					transaction.consumedCapacity().stream()
							.map(ConsumedCapacity::capacityUnits)
							.filter(java.util.Objects::nonNull)
							.mapToDouble(Double::doubleValue)
							.sum());
		}
		if (failure != null) {
			log.addKeyValue("error_type", failure.getClass().getName());
			if (!conflict && SafeException.shouldReportStack()) {
				log.setCause(SafeException.redactMessages(failure));
			}
		}
		log.log("database_call_completed");
	}

	private void recordCapacity(org.slf4j.spi.LoggingEventBuilder log, ConsumedCapacity capacity) {
		if (capacity != null && capacity.capacityUnits() != null) {
			log.addKeyValue("consumed_capacity_units", capacity.capacityUnits());
		}
	}

	private String identifyResourceFamily(SdkRequest request) {
		if (request instanceof GetItemRequest item) {
			return identifyPartition(item.key());
		}
		if (request instanceof QueryRequest query) {
			var value = query.expressionAttributeValues().get(":pk");
			return identifyPartitionName(value == null ? "" : value.s());
		}
		if (request instanceof ScanRequest) {
			return "legacy_orders";
		}
		if (request instanceof TransactWriteItemsRequest transaction) {
			var families = new HashSet<String>();
			for (var action : transaction.transactItems()) {
				if (action.put() != null) {
					families.add(identifyPartition(action.put().item()));
				}
				if (action.delete() != null) {
					families.add(identifyPartition(action.delete().key()));
				}
				if (action.conditionCheck() != null) {
					families.add(identifyPartition(action.conditionCheck().key()));
				}
			}
			return families.size() == 1 ? families.iterator().next() : "mixed";
		}
		return "table";
	}

	private String identifyPartition(Map<String, AttributeValue> key) {
		var partition = key.get("pk");
		return identifyPartitionName(partition == null ? "" : partition.s());
	}

	private String identifyPartitionName(String partition) {
		if (partition == null) {
			return "other";
		}
		String family =
				partition.startsWith("user#")
						? partition.substring(partition.lastIndexOf('#') + 1)
						: partition;
		return FAMILIES.contains(family) ? family : "other";
	}
}
