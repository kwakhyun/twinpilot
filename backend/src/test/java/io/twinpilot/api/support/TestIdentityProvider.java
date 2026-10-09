package io.twinpilot.api.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 최소 OIDC 발급자. JWKS 엔드포인트를 띄우고 Keycloak 형식(realm_access.roles)의 액세스 토큰을 서명한다.
 * 애플리케이션은 운영과 같은 설정 경로(jwk-set-uri + issuer-uri + audiences)로 토큰을 검증한다.
 */
public final class TestIdentityProvider {

    public static final String ISSUER = "https://idp.test/realms/twinpilot";
    public static final String AUDIENCE = "twinpilot-api";

    private static final TestIdentityProvider INSTANCE = new TestIdentityProvider();

    private final RSAKey key;
    private final RSAKey otherKey;
    private final HttpServer server;

    private TestIdentityProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            server.start();
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TestIdentityProvider get() {
        return INSTANCE;
    }

    public String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
    }

    public String token(String username, String... roles) {
        return sign(key, claims(username, ISSUER, List.of(AUDIENCE, "twinpilot-agent"), Instant.now().plusSeconds(300), roles));
    }

    public String tokenWithAudience(String username, String audience, String... roles) {
        return sign(key, claims(username, ISSUER, List.of(audience), Instant.now().plusSeconds(300), roles));
    }

    public String expiredToken(String username, String... roles) {
        return sign(key, claims(username, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(600), roles));
    }

    /** 같은 kid를 쓰지만 다른 키로 서명한 위조 토큰. */
    public String forgedToken(String username, String... roles) {
        return sign(otherKey, claims(username, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), roles));
    }

    private static JWTClaimsSet claims(String username, String issuer, List<String> audience, Instant exp,
                                       String... roles) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8)).toString())
                .audience(audience)
                .issueTime(java.util.Date.from(exp.minusSeconds(600)))
                .expirationTime(java.util.Date.from(exp))
                .claim("preferred_username", username)
                .claim("azp", "twinpilot-web")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
    }

    private static String sign(RSAKey signingKey, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
