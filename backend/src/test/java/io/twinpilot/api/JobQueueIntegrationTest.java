package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.job.JobWorker;
import io.twinpilot.api.support.IntegrationTestBase;

/** DB 기반 작업 큐: 비동기 실행, SSE 진행률, 멱등 키, 취소, 사용자 한도, 노드 중단 복구. */
class JobQueueIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobWorker worker;

    @Test
    void recommendationJobImprovesCoverageAndStreamsProgress() throws Exception {
        String token = IDP.token("analyst-sse", "PLANNER");
        RestClient analyst = client(token);
        var created = analyst.post().uri("/api/v1/analysis/jobs").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("type", "RECOMMEND_SITES", "facilityType", "HYDRANT",
                        "count", 3, "regionId", 3)))
                .retrieve().toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        String id = mapper.readTree(created.getBody()).path("id").asString();

        List<String> events = readSse("/api/v1/analysis/jobs/" + id + "/events", token, "done", Duration.ofSeconds(60));
        assertThat(events).contains("progress").last().isEqualTo("done");

        JsonNode job = mapper.readTree(analyst.get().uri("/api/v1/analysis/jobs/" + id).retrieve().body(String.class));
        assertThat(job.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(job.path("createdBy").asString()).isEqualTo("analyst-sse");
        JsonNode result = job.path("result");
        assertThat(result.path("sites").size()).isEqualTo(3);
        assertThat(result.path("coverageAfter").asDouble()).isGreaterThan(result.path("coverageBefore").asDouble());
        for (JsonNode site : result.path("sites")) {
            assertThat(site.path("regionId").asInt()).isEqualTo(3);
            assertThat(site.path("newlyCoveredBuildings").asInt()).isPositive();
        }

        // 추천 1순위를 계획 시설로 배치하면 includePlanned 커버리지가 그만큼 오른다.
        JsonNode site = result.path("sites").path(0);
        JsonNode planned = mapper.readTree(analyst.post().uri("/api/v1/facilities").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("type", "HYDRANT", "lon", site.path("lon").asDouble(),
                        "lat", site.path("lat").asDouble())))
                .retrieve().body(String.class));
        assertThat(planned.path("status").asString()).isEqualTo("PLANNED");
        JsonNode withPlan = post("/api/v1/analysis/coverage",
                Map.of("facilityType", "HYDRANT", "regionId", 3, "includePlanned", true, "includeBlindSpots", false));
        JsonNode withoutPlan = post("/api/v1/analysis/coverage",
                Map.of("facilityType", "HYDRANT", "regionId", 3, "includeBlindSpots", false));
        assertThat(withPlan.path("summary").path("coveredBuildings").asInt()
                - withoutPlan.path("summary").path("coveredBuildings").asInt())
                .isEqualTo(site.path("newlyCoveredBuildings").asInt());
        http.delete().uri("/api/v1/facilities/planned").retrieve().toBodilessEntity();

        // 내 Job 목록에는 결과 본문 없이 나온다.
        JsonNode mine = mapper.readTree(analyst.get().uri("/api/v1/analysis/jobs").retrieve().body(String.class));
        assertThat(mine.findValuesAsString("id")).contains(id);
        assertThat(mine.path(0).path("result").isNull()).isTrue();
    }

    @Test
    void idempotencyKeyReturnsTheSameJob() {
        RestClient analyst = as("analyst-idem", "ANALYST");
        String body = mapper.writeValueAsString(Map.of("type", "RECOMMEND_SITES", "facilityType", "AED", "count", 1));
        String first = mapper.readTree(analyst.post().uri("/api/v1/analysis/jobs").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class)).path("id").asString();
        String second = mapper.readTree(analyst.post().uri("/api/v1/analysis/jobs").header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class)).path("id").asString();
        assertThat(second).isEqualTo(first);
        // 키는 사용자별로 구분된다.
        String other = mapper.readTree(as("analyst-other", "ANALYST").post().uri("/api/v1/analysis/jobs")
                .header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class)).path("id").asString();
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void queuedJobCanBeCancelledOnlyByOwnerOrAdmin() throws Exception {
        UUID id = insertQueued("owner-sub", "owner", Duration.ofMinutes(10));
        // 다른 분석가는 취소할 수 없다.
        assertThat(status(as("someone-else", "ANALYST"), "DELETE", "/api/v1/analysis/jobs/" + id, null)).isEqualTo(403);
        // 다른 사용자는 Job과 진행 이벤트를 볼 수도 없다(있는지도 알리지 않는다).
        assertThat(status(as("someone-else", "ANALYST"), "GET", "/api/v1/analysis/jobs/" + id, null)).isEqualTo(404);
        assertThat(status(as("someone-else", "ANALYST"), "GET", "/api/v1/analysis/jobs/" + id + "/events", null))
                .isEqualTo(404);
        JsonNode cancelled = mapper.readTree(http.delete().uri("/api/v1/analysis/jobs/" + id).retrieve().body(String.class));
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(readSse("/api/v1/analysis/jobs/" + id + "/events", IDP.token("admin", "ADMIN"), "cancelled",
                Duration.ofSeconds(5))).last().isEqualTo("cancelled");
        assertThat(status(http, "DELETE", "/api/v1/analysis/jobs/" + id, null)).isEqualTo(409);
    }

    @Test
    void perUserActiveJobLimitReturns429() {
        String sub = UUID.nameUUIDFromBytes("busy-user".getBytes()).toString();
        for (int i = 0; i < 5; i++) {
            insertQueued(sub, "busy-user", Duration.ofMinutes(10));
        }
        var res = as("busy-user", "ANALYST").post().uri("/api/v1/analysis/jobs").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("type", "RECOMMEND_SITES", "facilityType", "AED")))
                .exchange((req, r) -> new Object[]{r.getStatusCode().value(), r.getHeaders().getFirst("Retry-After")});
        assertThat(res[0]).isEqualTo(429);
        assertThat(res[1]).isEqualTo("30");
        jdbc.sql("UPDATE analysis_job SET status = 'CANCELLED', finished_at = now() WHERE created_by = :s")
                .param("s", sub).update();
    }

    @Test
    void jobsOfCrashedNodeAreRetriedAfterLeaseExpiry() {
        // 다른 노드가 실행하다 죽은 상황: RUNNING인데 임대가 이미 만료됐다.
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO analysis_job (id, type, status, params, created_by, created_by_name, attempts,
                                                  locked_by, locked_until, started_at)
                        VALUES (:id, 'RECOMMEND_SITES', 'RUNNING', '{"facilityType":"AED","count":1,"regionId":2}',
                                'crash-sub', 'crash', 1, 'dead-node:1234', now() - interval '1 minute', now())""")
                .param("id", id).update();
        worker.reap();
        await().atMost(Duration.ofSeconds(30)).until(() -> "SUCCEEDED".equals(jobStatus(id)));
        JsonNode job = get("/api/v1/analysis/jobs/" + id);
        assertThat(job.path("attempts").asInt()).isEqualTo(2);
        assertThat(job.path("result").path("requested").asInt()).isEqualTo(1);

        // 재시도 한도를 다 쓴 Job은 실패로 끝난다.
        UUID exhausted = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO analysis_job (id, type, status, params, created_by, attempts, max_attempts,
                                                  locked_by, locked_until)
                        VALUES (:id, 'RECOMMEND_SITES', 'RUNNING', '{}', 'crash-sub', 3, 3, 'dead-node:1234',
                                now() - interval '1 minute')""")
                .param("id", exhausted).update();
        worker.reap();
        assertThat(jobStatus(exhausted)).isEqualTo("FAILED");
        assertThat(get("/api/v1/analysis/jobs/" + exhausted).path("error").asString()).contains("재시도 한도");
    }

    @Test
    void handlerErrorsFailWithoutRetry() {
        var created = mapper.readTree(http.post().uri("/api/v1/analysis/jobs").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("type", "RECOMMEND_SITES", "facilityType", "AED",
                        "regionId", 99999)))
                .retrieve().body(String.class));
        UUID id = UUID.fromString(created.path("id").asString());
        await().atMost(Duration.ofSeconds(30)).until(() -> List.of("SUCCEEDED", "FAILED").contains(jobStatus(id)));
        JsonNode job = get("/api/v1/analysis/jobs/" + id);
        // 존재하지 않는 행정동이면 후보가 없어 추천 0곳으로 성공하거나, 입력 오류로 실패한다. 어느 쪽이든 1회만 시도한다.
        assertThat(job.path("attempts").asInt()).isEqualTo(1);
    }

    private UUID insertQueued(String sub, String name, Duration delay) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO analysis_job (id, type, status, params, created_by, created_by_name, run_after)
                        VALUES (:id, 'RECOMMEND_SITES', 'QUEUED', '{}', :sub, :name,
                                now() + make_interval(secs => :delay))""")
                .param("id", id).param("sub", sub).param("name", name).param("delay", (double) delay.toSeconds())
                .update();
        return id;
    }

    private String jobStatus(UUID id) {
        return jdbc.sql("SELECT status FROM analysis_job WHERE id = :id").param("id", id).query(String.class).single();
    }
}
