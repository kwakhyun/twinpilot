package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.support.IntegrationTestBase;

/** 센서 수집 API, 리더 시뮬레이터, SSE 방송, 일 단위 파티션. */
class SensorIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Test
    void simulatorLeaderFeedsLatestStateAndStream() throws Exception {
        await().atMost(Duration.ofSeconds(15)).until(() -> get("/api/v1/sensors").path("total").asInt() > 100);
        JsonNode status = get("/api/v1/sensors?kind=AIR_QUALITY");
        assertThat(status.path("readings").size()).isEqualTo(4);
        assertThat(status.path("readings").path(0).path("values").has("pm25")).isTrue();
        assertThat(status.path("lastReading").asString()).isNotBlank();
        // 리더 임대는 한 노드만 가진다.
        assertThat(jdbc.sql("SELECT count(*) FROM leader_lease WHERE name = 'sensor-simulator' AND until > now()")
                .query(Integer.class).single()).isEqualTo(1);

        List<String> events = readSse("/api/v1/sensors/stream", IDP.token("viewer", "VIEWER"), "readings",
                Duration.ofSeconds(10));
        assertThat(events).contains("readings");
    }

    @Test
    void ingestValidatesEachReadingAndKeepsLatestByTimestamp() {
        RestClient gateway = as("service-account-sensor-gateway", "INGEST");
        int sensorId = jdbc.sql("SELECT id FROM sensor WHERE kind = 'HYDRANT_PRESSURE' ORDER BY id DESC LIMIT 1")
                .query(Integer.class).single();
        String code = jdbc.sql("SELECT code FROM sensor WHERE id = :id").param("id", sensorId).query(String.class).single();
        Instant future = Instant.now().plusSeconds(120);   // 시뮬레이터 값보다 늦은 시각 → 최신값으로 반영
        Map<String, Object> body = Map.of("readings", List.of(
                Map.of("sensorCode", code, "ts", future.toString(), "values", Map.of("pressureMpa", 0.05)),
                Map.of("sensorId", sensorId, "ts", future.minusSeconds(3600).toString(),
                        "values", Map.of("pressureMpa", 0.4)),
                Map.of("sensorId", 999999, "values", Map.of("pressureMpa", 0.3)),
                Map.of("sensorId", sensorId, "values", Map.of("pressureMpa", 99)),
                Map.of("sensorId", sensorId, "ts", "2020-01-01T00:00:00Z", "values", Map.of("pressureMpa", 0.3))));
        JsonNode result = mapper.readTree(gateway.post().uri("/api/v1/sensors/readings")
                .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body))
                .retrieve().body(String.class));
        assertThat(result.path("accepted").asInt()).isEqualTo(2);
        assertThat(result.path("rejected").asInt()).isEqualTo(3);
        assertThat(result.path("errors").size()).isEqualTo(3);

        // 서버가 상태를 판정하고(0.05MPa < 0.15 → ALERT), 늦게 도착한 과거 측정값은 최신값을 덮어쓰지 않는다.
        await().atMost(Duration.ofSeconds(5)).until(() -> reading(sensorId).path("status").asString().equals("ALERT"));
        assertThat(reading(sensorId).path("values").path("pressureMpa").asDouble()).isEqualTo(0.05);

        // 같은 (센서, 시각)을 다시 보내면 이력에 중복 저장하지 않는다.
        gateway.post().uri("/api/v1/sensors/readings").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("readings", List.of(Map.of("sensorId", sensorId,
                        "ts", future.toString(), "values", Map.of("pressureMpa", 0.05))))))
                .retrieve().toBodilessEntity();
        assertThat(jdbc.sql("SELECT count(*) FROM sensor_reading WHERE sensor_id = :id AND ts = :ts")
                .param("id", sensorId).param("ts", java.sql.Timestamp.from(future)).query(Integer.class).single())
                .isEqualTo(1);

        JsonNode history = get("/api/v1/sensors/" + sensorId + "/history?limit=5");
        assertThat(history.path(0).path("value").path("pressureMpa").asDouble()).isEqualTo(0.05);
        assertThat(history.path(0).path("status").asString()).isEqualTo("ALERT");

        // 이동형 센서는 위치가 필수
        int bus = jdbc.sql("SELECT id FROM sensor WHERE kind = 'AIR_QUALITY' LIMIT 1").query(Integer.class).single();
        JsonNode noPos = mapper.readTree(gateway.post().uri("/api/v1/sensors/readings")
                .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(Map.of("readings",
                        List.of(Map.of("sensorId", bus, "values", Map.of("pm25", 10, "pm10", 20))))))
                .retrieve().body(String.class));
        assertThat(noPos.path("rejected").asInt()).isEqualTo(1);
    }

    @Test
    void readingsArePartitionedByDay() {
        String today = "sensor_reading_p" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        List<String> partitions = jdbc.sql("""
                        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'sensor_reading'""")
                .query(String.class).list();
        assertThat(partitions).contains(today, "sensor_reading_default").hasSizeGreaterThanOrEqualTo(5);
        // 오늘 측정값은 DEFAULT가 아니라 오늘 파티션에 들어간다.
        await().atMost(Duration.ofSeconds(10)).until(() ->
                jdbc.sql("SELECT count(*) FROM " + today).query(Integer.class).single() > 0);
        assertThat(jdbc.sql("SELECT count(*) FROM sensor_reading_default WHERE ts > now() - interval '1 hour'")
                .query(Integer.class).single()).isZero();
    }

    private JsonNode reading(int sensorId) {
        for (JsonNode r : get("/api/v1/sensors").path("readings")) {
            if (r.path("sensorId").asInt() == sensorId) {
                return r;
            }
        }
        return mapper.createObjectNode();
    }
}
