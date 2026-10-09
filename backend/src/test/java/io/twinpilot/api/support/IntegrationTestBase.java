package io.twinpilot.api.support;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 통합 테스트 공통 기반. 실제 PostGIS 컨테이너 하나를 모든 테스트 클래스가 공유한다(스프링 컨텍스트도 캐시로 공유).
 * 토큰은 {@link TestIdentityProvider}가 발급하고 애플리케이션은 운영과 같은 JWT 검증 설정을 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGIS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGIS::stop));
    }

    protected static final TestIdentityProvider IDP = TestIdentityProvider.get();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestIdentityProvider.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDP::jwkSetUri);
        registry.add("management.server.port", () -> "0");
        registry.add("twinpilot.storage.local-root", () -> "target/test-storage");
        registry.add("twinpilot.sensors.interval-ms", () -> "300");
        registry.add("twinpilot.jobs.poll-interval", () -> "500ms");
    }

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    @Autowired
    protected ObjectMapper mapper;

    /** ADMIN 권한 클라이언트(대부분의 기능 테스트용). */
    protected RestClient http;

    @BeforeEach
    void setUpClient() {
        http = client(IDP.token("admin", "ADMIN"));
    }

    protected RestClient client(String token) {
        // JDK 클라이언트를 명시한다(Apache HttpClient 5는 429의 Retry-After를 보고 자동 재시도해 응답 검증을 가린다).
        RestClient.Builder builder = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port);
        if (token != null) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    protected RestClient as(String username, String... roles) {
        return client(IDP.token(username, roles));
    }

    protected JsonNode get(String path) {
        return mapper.readTree(http.get().uri(path).retrieve().body(String.class));
    }

    protected JsonNode post(String path, Object body) {
        return mapper.readTree(http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body)).retrieve().body(String.class));
    }

    protected int status(RestClient client, String method, String path, Object body) {
        var spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
        }
        return spec.exchange((req, res) -> res.getStatusCode().value());
    }

    /** SSE를 읽어 이벤트 이름 목록을 돌려준다. {@code until} 이벤트를 받으면 멈춘다. */
    protected List<String> readSse(String path, String token, String until, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").timeout(timeout);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<java.io.InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        List<String> events = new ArrayList<>();
        if (response.statusCode() != 200) {
            events.add("HTTP " + response.statusCode());
            return events;
        }
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    String name = line.substring(6).trim();
                    events.add(name);
                    if (name.equals(until)) {
                        break;
                    }
                }
            }
        }
        return events;
    }
}
