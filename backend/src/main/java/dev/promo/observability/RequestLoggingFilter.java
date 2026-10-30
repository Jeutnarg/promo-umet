package dev.promo.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Correlates request arrival and completion, including security and body-limit responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {
	private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
	private final RequestRouteCatalog routes;

	public RequestLoggingFilter(RequestRouteCatalog routes) {
		this.routes = routes;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var previousMdc = MDC.getCopyOfContextMap();
		String requestId = UUID.randomUUID().toString();
		var diagnostics = RequestDiagnostics.beginRequest(requestId);
		response.setHeader("X-Request-ID", requestId);
		long started = System.nanoTime();
		boolean failed = false;
		Level normalLevel =
				"/actuator/health".equals(request.getRequestURI()) ? Level.DEBUG : Level.INFO;
		var registeredRoute = routes.identifyRoute(request);
		MDC.put("api_route", registeredRoute.pattern());
		MDC.put("api_operation", registeredRoute.operation());
		try {
			LOG.atLevel(normalLevel)
					.addKeyValue("http_method", getSafeMethod(request))
					.addKeyValue("resource", classifyResource(request))
					.addKeyValue("route", registeredRoute.pattern())
					.addKeyValue("operation", registeredRoute.operation())
					.log("request_received");
			chain.doFilter(request, response);
		} catch (IOException | ServletException | RuntimeException | Error exception) {
			failed = true;
			var event = LOG.atError().addKeyValue("error_type", exception.getClass().getName());
			if (SafeException.shouldReportStack()) {
				event.setCause(SafeException.redactMessages(exception));
			}
			event.log("request_exception");
			throw exception;
		} finally {
			try {
				logRequestCompletion(
						request, response, diagnostics, registeredRoute, started, failed, normalLevel);
			} finally {
				RequestDiagnostics.finishRequest();
				if (previousMdc == null) {
					MDC.clear();
				} else {
					MDC.setContextMap(previousMdc);
				}
			}
		}
	}

	private void logRequestCompletion(
			HttpServletRequest request,
			HttpServletResponse response,
			RequestDiagnostics diagnostics,
			RequestRouteCatalog.Route registeredRoute,
			long started,
			boolean failed,
			Level normalLevel) {
		int status = failed ? 500 : response.getStatus();
		String route =
				request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
								instanceof String pattern
						? pattern
						: registeredRoute.pattern();
		String operation =
				request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)
								instanceof HandlerMethod handler
						? handler.getMethod().getName()
						: registeredRoute.operation();
		MDC.put("api_route", route);
		MDC.put("api_operation", operation);
		LOG.atLevel(status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : normalLevel)
				.addKeyValue("http_method", getSafeMethod(request))
				.addKeyValue("resource", classifyResource(request))
				.addKeyValue("route", route)
				.addKeyValue("operation", operation)
				.addKeyValue("http_status", status)
				.addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0)
				.addKeyValue("db_calls", diagnostics.getDatabaseCalls())
				.addKeyValue("db_attempts", diagnostics.getDatabaseAttempts())
				.addKeyValue("db_duration_ms", diagnostics.getDatabaseNanos() / 1_000_000.0)
				.addKeyValue("db_failures", diagnostics.getDatabaseFailures())
				.addKeyValue("pricing_evaluations", diagnostics.getPricingEvaluations())
				.addKeyValue("pricing_duration_ms", diagnostics.getPricingNanos() / 1_000_000.0)
				.addKeyValue("optimization_calls", diagnostics.getOptimizationCalls())
				.addKeyValue("optimization_duration_ms", diagnostics.getOptimizationNanos() / 1_000_000.0)
				.addKeyValue("optimization_candidates", diagnostics.getOptimizationCandidates())
				.addKeyValue("error_code", resolveErrorCode(diagnostics, status))
				.log("request_completed");
	}

	private String resolveErrorCode(RequestDiagnostics diagnostics, int status) {
		if (!diagnostics.getErrorCode().equals("none")) {
			return diagnostics.getErrorCode();
		}
		return switch (status) {
			case 401 -> "unauthenticated";
			case 403 -> "forbidden";
			case 404 -> "not_found";
			case 413 -> "payload_too_large";
			default -> status >= 400 ? "http_error" : "none";
		};
	}

	private String getSafeMethod(HttpServletRequest request) {
		return switch (request.getMethod()) {
			case "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD" -> request.getMethod();
			default -> "OTHER";
		};
	}

	private String classifyResource(HttpServletRequest request) {
		String path = request.getRequestURI();
		for (String resource : new String[] {"items", "carts", "orders", "promotions", "admin"}) {
			if (path.equals("/api/v1/" + resource) || path.startsWith("/api/v1/" + resource + "/")) {
				return resource;
			}
		}
		return path.startsWith("/actuator/") ? "management" : "other";
	}
}
