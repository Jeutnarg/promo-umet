package dev.promo.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Identifies registered operations before security without logging raw request paths. */
@Component
public class RequestRouteCatalog {
	public record Route(String pattern, String operation) {}

	private record RegisteredRoute(Set<String> methods, PathPattern path, String operation) {}

	private volatile List<RegisteredRoute> routes = List.of();

	@EventListener
	public void registerRoutes(ApplicationReadyEvent event) {
		var mappings =
				event
						.getApplicationContext()
						.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
		routes =
				mappings.getHandlerMethods().entrySet().stream()
						.flatMap(
								entry ->
										entry.getKey().getPatternValues().stream()
												.map(
														pattern ->
																new RegisteredRoute(
																		entry.getKey().getMethodsCondition().getMethods().stream()
																				.map(Enum::name)
																				.collect(Collectors.toUnmodifiableSet()),
																		PathPatternParser.defaultInstance.parse(pattern),
																		entry.getValue().getMethod().getName())))
						.sorted(
								(first, second) ->
										PathPattern.SPECIFICITY_COMPARATOR.compare(first.path(), second.path()))
						.toList();
	}

	public Route identifyRoute(HttpServletRequest request) {
		try {
			var path = PathContainer.parsePath(request.getRequestURI());
			return routes.stream()
					.filter(
							route -> route.methods().isEmpty() || route.methods().contains(request.getMethod()))
					.filter(route -> route.path().matches(path))
					.findFirst()
					.map(route -> new Route(route.path().getPatternString(), route.operation()))
					.orElse(new Route("unmatched", "unmapped"));
		} catch (IllegalArgumentException malformedPath) {
			return new Route("unmatched", "unmapped");
		}
	}
}
