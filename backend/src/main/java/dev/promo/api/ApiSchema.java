package dev.promo.api;

import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Defines required fields for the generated API contract. */
@Configuration
public class ApiSchema {
	@Bean
	OpenApiCustomizer configureRequiredRecordFields() {
		var optional =
				Map.of(
						"Cart", Set.of("savedAt"),
						"CartSummary", Set.of("savedAt"),
						"PromotionUsage", Set.of("lastUsedAt"),
						"Promotion", Set.of("expiresAt", "description"),
						"Selection", Set.of("lineId", "pairs"),
						"Details", Set.of("amount", "basisPoints", "threshold", "categories"),
						"Evaluation", Set.of("nextExpiration"),
						"TestCase", Set.of("expectedCoveredUnits"),
						"TestResult", Set.of("error", "optimalityProven"));
		return api ->
				api.getComponents()
						.getSchemas()
						.forEach(
								(name, schema) -> {
									if (schema.getProperties() != null) {
										schema.setRequired(
												schema.getProperties().keySet().stream()
														.filter(key -> !optional.getOrDefault(name, Set.of()).contains(key))
														.toList());
									}
								});
	}
}
