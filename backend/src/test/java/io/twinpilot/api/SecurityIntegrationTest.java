package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.support.IntegrationTestBase;

/** 인증(JWT 검증)과 역할 기반 인가. */
class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void rejectsMissingInvalidAndMisaddressedTokens() {
        ResponseEntity<String> anonymous = client(null).get().uri("/api/v1/regions")
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode()).headers(res.getHeaders())
                        .body(new String(res.getBody().readAllBytes())));
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(anonymous.getHeaders().getFirst("WWW-Authenticate")).startsWith("Bearer");
        assertThat(anonymous.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(mapper.readTree(anonymous.getBody()).path("status").asInt()).isEqualTo(401);

        assertThat(status(client(IDP.expiredToken("old", "ADMIN")), "GET", "/api/v1/regions", null)).isEqualTo(401);
        assertThat(status(client(IDP.forgedToken("mallory", "ADMIN")), "GET", "/api/v1/regions", null)).isEqualTo(401);
        // 다른 서비스(에이전트)용으로만 발급된 토큰은 API가 받지 않는다.
        assertThat(status(client(IDP.tokenWithAudience("agent-only", "twinpilot-agent", "ADMIN")), "GET",
                "/api/v1/regions", null)).isEqualTo(401);
        assertThat(status(client("not-a-jwt"), "GET", "/api/v1/regions", null)).isEqualTo(401);
    }

    @Test
    void meReportsEffectiveRolesThroughHierarchy() {
        JsonNode me = mapper.readTree(as("planner1", "PLANNER").get().uri("/api/v1/me").retrieve().body(String.class));
        assertThat(me.path("username").asString()).isEqualTo("planner1");
        assertThat(me.path("roles")).extracting(JsonNode::asString)
                .containsExactlyInAnyOrder("PLANNER", "ANALYST", "VIEWER");

        // IdP의 기본 역할(offline_access 등)은 권한으로 들어오지 않는다.
        JsonNode viewer = mapper.readTree(as("v", "VIEWER", "offline_access", "uma_authorization").get()
                .uri("/api/v1/me").retrieve().body(String.class));
        assertThat(viewer.path("roles")).extracting(JsonNode::asString).containsExactly("VIEWER");
    }

    @Test
    void rolesGateEachCapability() {
        RestClient viewer = as("viewer1", "VIEWER");
        RestClient analyst = as("analyst1", "ANALYST");
        RestClient planner = as("planner1", "PLANNER");
        RestClient nobody = as("nobody");

        assertThat(status(nobody, "GET", "/api/v1/regions", null)).isEqualTo(403);
        assertThat(status(viewer, "GET", "/api/v1/buildings/search?limit=1", null)).isEqualTo(200);
        assertThat(status(viewer, "POST", "/api/v1/analysis/coverage", Map.of("facilityType", "AED"))).isEqualTo(200);

        Map<String, Object> recommend = Map.of("type", "RECOMMEND_SITES", "facilityType", "AED", "count", 1, "regionId", 1);
        assertThat(status(viewer, "POST", "/api/v1/analysis/jobs", recommend)).isEqualTo(403);
        assertThat(status(analyst, "POST", "/api/v1/analysis/jobs", recommend)).isEqualTo(202);
        // 위험도 일괄 재계산은 ADMIN 전용(유형별 세부 권한)
        assertThat(status(analyst, "POST", "/api/v1/analysis/jobs", Map.of("type", "REFRESH_RISK"))).isEqualTo(403);

        Map<String, Object> plan = Map.of("type", "AED", "lon", 129.056, "lat", 35.158);
        assertThat(status(analyst, "POST", "/api/v1/facilities", plan)).isEqualTo(403);
        assertThat(status(planner, "POST", "/api/v1/facilities", plan)).isEqualTo(201);
        assertThat(status(planner, "DELETE", "/api/v1/facilities/planned", null)).isEqualTo(200);

        assertThat(status(planner, "GET", "/api/v1/admin/audit", null)).isEqualTo(403);
        assertThat(status(viewer, "POST", "/api/v1/sensors/readings", Map.of("readings", java.util.List.of())))
                .isEqualTo(403);
        // 규칙에 없는 메서드·경로는 거부
        assertThat(status(planner, "PATCH", "/api/v1/regions", Map.of())).isEqualTo(403);
    }

    @Test
    void probesAndApiDocsArePublicButOnlyOnTheirPorts() {
        RestClient mgmt = RestClient.create("http://localhost:" + managementPort);
        JsonNode readiness = mapper.readTree(mgmt.get().uri("/actuator/health/readiness").retrieve().body(String.class));
        assertThat(readiness.path("status").asString()).isEqualTo("UP");
        assertThat(mgmt.get().uri("/actuator/prometheus").retrieve().body(String.class))
                .contains("jvm_threads").contains("twinpilot_jobs_queued");
        // 관리 엔드포인트는 서비스 포트에서 열리지 않는다(Ingress 노출 방지).
        assertThat(status(client(null), "GET", "/actuator/prometheus", null)).isIn(401, 403, 404);

        JsonNode docs = mapper.readTree(client(null).get().uri("/api-docs").retrieve().body(String.class));
        assertThat(docs.path("components").path("securitySchemes").has("oidc")).isTrue();
    }

    @Test
    void securityHeadersOnApiResponses() {
        var res = http.get().uri("/api/v1/me").retrieve().toEntity(String.class);
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeaders().getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
    }
}
