package dev.promo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtValidationTest {
	@ParameterizedTest
	@ValueSource(strings = {"valid", "expired", "issuer", "audience", "id-token", "missing-subject"})
	void validatesSignedTokens(String scenario) throws Exception {
		var key = new RSAKeyGenerator(2048).keyID("test-key").generate();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(
				"/jwks",
				exchange -> {
					var bytes =
							new JWKSet(key.toPublicJWK())
									.toString()
									.getBytes(java.nio.charset.StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, bytes.length);
					exchange.getResponseBody().write(bytes);
					exchange.close();
				});
		server.start();
		try {
			var claims =
					new JWTClaimsSet.Builder()
							.subject(scenario.equals("missing-subject") ? null : "owner")
							.issuer(scenario.equals("issuer") ? "wrong" : "issuer")
							.audience(scenario.equals("audience") ? "wrong" : "audience")
							.issueTime(Date.from(Instant.now().minusSeconds(600)))
							.expirationTime(
									Date.from(Instant.now().plusSeconds(scenario.equals("expired") ? -300 : 300)));
			if (scenario.equals("id-token")) {
				claims.claim("token_use", "id");
			}
			var signed =
					new SignedJWT(
							new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
			signed.sign(new RSASSASigner(key));
			var decoder =
					new Configuration()
							.createJwtDecoder(
									"issuer",
									"http://127.0.0.1:" + server.getAddress().getPort() + "/jwks",
									"audience");
			if (scenario.equals("valid")) {
				assertThat(decoder.decode(signed.serialize()).getSubject()).isEqualTo("owner");
			} else {
				assertThatThrownBy(() -> decoder.decode(signed.serialize()))
						.isInstanceOf(JwtException.class);
			}
		} finally {
			server.stop(0);
		}
	}
}
