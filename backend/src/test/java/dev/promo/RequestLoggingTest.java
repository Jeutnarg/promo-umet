package dev.promo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.promo.api.ApiError;
import dev.promo.observability.PricingTelemetry;
import dev.promo.observability.RequestDiagnostics;
import dev.promo.observability.RequestLoggingFilter;
import dev.promo.store.DynamoStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestLoggingTest {
	private static final String PRIVATE = "PRIVATE_PASSWORD_AND_CART_NAME";
	@Autowired private MockMvc mvc;
	@MockitoBean private DynamoStore store;
	private LogCapture capture;

	@BeforeEach
	void captureDiagnosticEvents() {
		capture = new LogCapture(RequestLoggingFilter.class, PricingTelemetry.class, ApiError.class);
	}

	@AfterEach
	void stopCapturingEvents() {
		capture.close();
	}

	@Test
	void requestPricingAndCompletionShareIdWithoutExposingInputData() throws Exception {
		String input =
				"{\"id\":\"ignored\",\"version\":0,\"lines\":[{\"id\":\"private-line\",\"itemId\":\"private-item\",\"name\":\""
						+ PRIVATE
						+ "\",\"quantity\":2,\"price\":1000,\"category\":\"private-category\"}],\"shipping\":1000,\"selections\":[],\"completed\":false}";
		var response =
				mvc.perform(
								post("/api/v1/carts")
										.queryParam("password", PRIVATE)
										.header("X-Request-ID", PRIVATE)
										.contentType("application/json")
										.content(input)
										.with(jwt().jwt(token -> token.subject(PRIVATE))))
						.andExpect(status().isOk())
						.andReturn()
						.getResponse();
		String id = response.getHeader("X-Request-ID");
		assertThat(id).matches("[a-f0-9-]{36}");
		assertThat(capture.findEvents("request_received")).hasSize(1);
		assertThat(capture.findEvents("request_completed")).hasSize(1);
		assertThat(capture.findEvents("pricing_evaluation_completed")).hasSize(1);
		assertThat(capture.getEvents())
				.allMatch(event -> id.equals(event.getMDCPropertyMap().get("request_id")));
		var summary = capture.findEvents("pricing_evaluation_completed").getFirst();
		assertThat(LogCapture.getField(summary, "unique_items")).isEqualTo(1L);
		assertThat(LogCapture.getField(summary, "units")).isEqualTo(2);
		assertThat(LogCapture.getField(summary, "applied_promotions")).isEqualTo(0L);
		var completion = capture.findEvents("request_completed").getFirst();
		assertThat(LogCapture.getField(completion, "operation")).isEqualTo("createCart");
		assertThat(LogCapture.getField(completion, "pricing_evaluations")).isEqualTo(1L);
		assertThat(capture.renderCapturedData())
				.doesNotContain(PRIVATE, "private-category", "private-item", "private-line");
		assertThat(RequestDiagnostics.currentRequest()).isNull();
		assertThat(MDC.get("request_id")).isNull();
	}

	@Test
	void rejectedRequestsRetainCorrelationAndRegisteredOperation() throws Exception {
		var unauthorized =
				mvc.perform(
								get("/api/v1/admin/dashboard/promotions")
										.header("Authorization", "Bearer " + PRIVATE))
						.andExpect(status().isUnauthorized())
						.andReturn()
						.getResponse();
		var forbidden =
				mvc.perform(
								get("/api/v1/admin/dashboard/promotions")
										.with(jwt().jwt(token -> token.subject("second-user"))))
						.andExpect(status().isForbidden())
						.andReturn()
						.getResponse();
		assertThat(unauthorized.getHeader("X-Request-ID"))
				.isNotEqualTo(forbidden.getHeader("X-Request-ID"));
		assertThat(capture.findEvents("request_completed"))
				.hasSize(2)
				.allMatch(event -> LogCapture.getField(event, "operation").equals("getPromotionDashboard"));
		assertThat(
						capture.findEvents("request_completed").getFirst().getMDCPropertyMap().get("actor_ref"))
				.isEqualTo("anonymous");
		assertThat(capture.renderCapturedData()).doesNotContain(PRIVATE);
	}

	@Test
	void unexpectedFailuresRetainStackLocationsButExcludeExceptionMessages() throws Exception {
		when(store.findDocument(anyString(), anyString()))
				.thenThrow(new IllegalStateException(PRIVATE, new IllegalArgumentException(PRIVATE)));
		mvc.perform(get("/api/v1/carts/" + PRIVATE).with(jwt()))
				.andExpect(status().isInternalServerError());
		assertThat(capture.findEvents("api_error")).hasSize(1);
		assertThat(capture.findEvents("api_error").getFirst().getThrowableProxy()).isNotNull();
		assertThat(capture.renderCapturedData())
				.contains("IllegalStateException", "IllegalArgumentException")
				.doesNotContain(PRIVATE);
		assertThat(LogCapture.getField(capture.findEvents("request_completed").getFirst(), "route"))
				.isEqualTo("/api/v1/carts/{id}");
	}

	@Test
	void malformedOversizedAndUnmappedRequestsKeepTheirCorrectStatuses() throws Exception {
		mvc.perform(
						post("/api/v1/carts")
								.with(jwt())
								.contentType("application/json")
								.content("{\"secret\":\"" + PRIVATE))
				.andExpect(status().isBadRequest());
		mvc.perform(
						post("/api/v1/carts")
								.with(jwt())
								.contentType("application/json")
								.content(" ".repeat(262145)))
				.andExpect(status().isPayloadTooLarge());
		mvc.perform(get("/missing/" + PRIVATE).with(jwt())).andExpect(status().isNotFound());
		assertThat(capture.findEvents("request_completed")).hasSize(3);
		assertThat(capture.renderCapturedData()).doesNotContain(PRIVATE);
		assertThat(RequestDiagnostics.currentRequest()).isNull();
	}

	@Test
	void performanceMetricsRequireAnAdministrator() throws Exception {
		mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/metrics").with(jwt())).andExpect(status().isForbidden());
		mvc.perform(
						get("/actuator/metrics")
								.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
				.andExpect(status().isOk());
	}
}
