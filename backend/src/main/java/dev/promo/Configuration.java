package dev.promo;

import dev.promo.domain.Optimizer;
import dev.promo.domain.PricingEngine;
import dev.promo.domain.PricingObserver;
import dev.promo.observability.ActorContextFilter;
import dev.promo.observability.DatabaseTelemetry;
import dev.promo.observability.PricingTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Configures pricing, persistence, and authenticated API access. */
@org.springframework.context.annotation.Configuration
public class Configuration {
	@Bean
	Clock createSystemClock() {
		return Clock.systemUTC();
	}

	@Bean
	PricingEngine createPricingEngine(PricingObserver observer) {
		return new PricingEngine(observer);
	}

	@Bean
	Optimizer createPromotionOptimizer(PricingEngine engine, PricingObserver observer) {
		return new Optimizer(engine, observer);
	}

	@Bean
	PricingObserver createPricingObserver(MeterRegistry metrics) {
		return new PricingTelemetry(metrics);
	}

	@Bean
	DynamoDbClient createDynamoClient(
			@Value("${sandbox.endpoint:}") String endpoint,
			@Value("${sandbox.region:us-east-1}") String region,
			MeterRegistry metrics) {
		var builder =
				DynamoDbClient.builder()
						.region(Region.of(region))
						.httpClientBuilder(UrlConnectionHttpClient.builder())
						.overrideConfiguration(
								configuration ->
										configuration.addExecutionInterceptor(new DatabaseTelemetry(metrics)));
		if (!endpoint.isBlank()) {
			builder
					.endpointOverride(URI.create(endpoint))
					.credentialsProvider(
							StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
		} else {
			builder.credentialsProvider(DefaultCredentialsProvider.create());
		}
		return builder.build();
	}

	@Bean
	JwtDecoder createJwtDecoder(
			@Value("${sandbox.issuer}") String issuer,
			@Value("${sandbox.jwks}") String jwks,
			@Value("${sandbox.audience}") String audience) {
		var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
		OAuth2TokenValidator<Jwt> audienceValidator =
				token ->
						token.getSubject() != null
										&& (token.getClaimAsString("token_use") == null
												|| "access".equals(token.getClaimAsString("token_use")))
										&& (token.getAudience().contains(audience)
												|| ("access".equals(token.getClaimAsString("token_use"))
														&& audience.equals(token.getClaimAsString("client_id"))))
								? OAuth2TokenValidatorResult.success()
								: OAuth2TokenValidatorResult.failure(
										new OAuth2Error("invalid_token", "Invalid audience", null));
		decoder.setJwtValidator(
				new DelegatingOAuth2TokenValidator<>(
						JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
		return decoder;
	}

	@Bean
	SecurityFilterChain configureSecurity(
			HttpSecurity http, @Value("${sandbox.origins}") List<String> origins) throws Exception {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(origins);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
		cors.setExposedHeaders(List.of("X-Request-ID"));
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(
				jwt -> {
					var roles = new HashSet<String>();
					Map<String, Object> realm = jwt.getClaim("realm_access");
					if (realm != null && realm.get("roles") instanceof List<?> values) {
						values.forEach(value -> roles.add(value.toString()));
					}
					List<String> groups = jwt.getClaimAsStringList("cognito:groups");
					if (groups != null) {
						roles.addAll(groups);
					}
					return roles.stream()
							.map(role -> new SimpleGrantedAuthority("ROLE_" + role))
							.map(org.springframework.security.core.GrantedAuthority.class::cast)
							.toList();
				});
		return http.csrf(csrf -> csrf.disable())
				.cors(c -> c.configurationSource(source))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(
						auth ->
								auth.requestMatchers("/actuator/health", "/v3/api-docs/**")
										.permitAll()
										.requestMatchers("/api/v1/admin/**")
										.hasRole("admin")
										.requestMatchers("/actuator/metrics", "/actuator/metrics/**")
										.hasRole("admin")
										.anyRequest()
										.authenticated())
				.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
				.addFilterAfter(new ActorContextFilter(), BearerTokenAuthenticationFilter.class)
				.build();
	}
}
