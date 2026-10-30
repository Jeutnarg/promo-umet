package dev.promo.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds buffered request bodies before controllers read them. */
@Component
public class BodyLimit extends OncePerRequestFilter {
	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!request.getMethod().equals("POST")
				&& !request.getMethod().equals("PUT")
				&& !request.getMethod().equals("DELETE")) {
			chain.doFilter(request, response);
			return;
		}
		var bytes = request.getInputStream().readNBytes(262145);
		if (bytes.length > 262144) {
			response.setStatus(413);
			response.setContentType("application/json");
			response
					.getWriter()
					.write(
							"{\"code\":\"payload_too_large\",\"message\":\"Maximum request size is 256 KiB\"}");
			return;
		}
		chain.doFilter(
				new HttpServletRequestWrapper(request) {
					@Override
					public ServletInputStream getInputStream() {
						var input = new ByteArrayInputStream(bytes);
						return new ServletInputStream() {
							@Override
							public int read() {
								return input.read();
							}

							@Override
							public boolean isFinished() {
								return input.available() == 0;
							}

							@Override
							public boolean isReady() {
								return true;
							}

							@Override
							public void setReadListener(ReadListener listener) {
								throw new UnsupportedOperationException();
							}
						};
					}
				},
				response);
	}
}
