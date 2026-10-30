package dev.promo.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds a stable pseudonymous actor reference only after bearer-token validation. */
public class ActorContextFilter extends OncePerRequestFilter {
	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication()
						instanceof JwtAuthenticationToken token
				&& token.isAuthenticated()) {
			var diagnostics = RequestDiagnostics.currentRequest();
			if (diagnostics != null) {
				diagnostics.identifyActor(
						String.valueOf(token.getToken().getIssuer()), token.getToken().getSubject());
				org.slf4j.MDC.put(
						"actor_type",
						token.getAuthorities().stream()
										.anyMatch(authority -> authority.getAuthority().equals("ROLE_admin"))
								? "admin"
								: "user");
			}
		}
		chain.doFilter(request, response);
	}
}
