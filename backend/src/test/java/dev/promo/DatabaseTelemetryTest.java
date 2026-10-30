package dev.promo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.promo.observability.DatabaseTelemetry;
import dev.promo.observability.RequestDiagnostics;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class DatabaseTelemetryTest {
	@AfterEach
	void clearRequestContext() {
		RequestDiagnostics.finishRequest();
		MDC.clear();
	}

	@Test
	void databaseEventsCountPagesAttemptsAndCapacityWithoutLeakingKeys() {
		try (var capture = new LogCapture(DatabaseTelemetry.class);
				var registry = new TestMeterRegistry()) {
			var telemetry = new DatabaseTelemetry(registry);
			var diagnostics = RequestDiagnostics.beginRequest("database-test");
			var request =
					QueryRequest.builder()
							.tableName("private-table")
							.expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("user#PRIVATE#carts")))
							.build();
			for (int page = 0; page < 2; page++) {
				var attributes =
						new ExecutionAttributes().putAttribute(SdkExecutionAttribute.OPERATION_NAME, "Query");
				telemetry.beforeExecution(mock(Context.BeforeExecution.class), attributes);
				telemetry.beforeTransmission(mock(Context.BeforeTransmission.class), attributes);
				telemetry.beforeTransmission(mock(Context.BeforeTransmission.class), attributes);
				var completed = mock(Context.AfterExecution.class);
				when(completed.request()).thenReturn(request);
				when(completed.response())
						.thenReturn(
								QueryResponse.builder()
										.count(3)
										.scannedCount(5)
										.consumedCapacity(ConsumedCapacity.builder().capacityUnits(2.0).build())
										.build());
				telemetry.afterExecution(completed, attributes);
			}
			assertThat(diagnostics.getDatabaseCalls()).isEqualTo(2);
			assertThat(diagnostics.getDatabaseAttempts()).isEqualTo(4);
			assertThat(diagnostics.getDatabaseNanos()).isPositive();
			assertThat(
							registry
									.get("sandbox.database.duration")
									.tags("operation", "Query", "resource", "carts", "outcome", "success")
									.timer()
									.count())
					.isEqualTo(2);
			assertThat(capture.findEvents("database_call_completed"))
					.hasSize(2)
					.allMatch(event -> LogCapture.getField(event, "consumed_capacity_units").equals(2.0));
			assertThat(capture.renderCapturedData()).doesNotContain("PRIVATE", "private-table");
		}
	}

	@Test
	void databaseFailuresHaveSafeTracesAndErrorMetrics() {
		try (var capture = new LogCapture(DatabaseTelemetry.class);
				var registry = new TestMeterRegistry()) {
			var telemetry = new DatabaseTelemetry(registry);
			var diagnostics = RequestDiagnostics.beginRequest("database-error-test");
			var attributes =
					new ExecutionAttributes().putAttribute(SdkExecutionAttribute.OPERATION_NAME, "Query");
			telemetry.beforeExecution(mock(Context.BeforeExecution.class), attributes);
			var failed = mock(Context.FailedExecution.class);
			when(failed.exception()).thenReturn(SdkClientException.create("PRIVATE_PASSWORD"));
			telemetry.onExecutionFailure(failed, attributes);
			assertThat(diagnostics.getDatabaseFailures()).isEqualTo(1);
			assertThat(capture.findEvents("database_call_completed").getFirst().getThrowableProxy())
					.isNotNull();
			assertThat(capture.renderCapturedData()).doesNotContain("PRIVATE_PASSWORD");
			assertThat(registry.get("sandbox.database.duration").tag("outcome", "error").timer().count())
					.isEqualTo(1);
		}
	}
}
