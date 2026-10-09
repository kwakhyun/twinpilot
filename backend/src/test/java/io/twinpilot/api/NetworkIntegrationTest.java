package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.support.IntegrationTestBase;

/** 도로망 그래프, 출동 시간, 도달권. */
class NetworkIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Test
    void seedBuildsNetworkAndRoadBasedResponseTimes() {
        JsonNode state = get("/api/v1/analysis/network");
        assertThat(state.path("nodes").asInt()).isGreaterThan(20);
        assertThat(state.path("edges").asInt()).isGreaterThan(state.path("nodes").asInt());
        assertThat(state.path("stale").asBoolean()).isFalse();

        assertThat(count("SELECT count(*) FROM building WHERE response_s IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM building WHERE risk_factors ->> 'responseMethod' <> 'ROAD_NETWORK'")).isZero();
        // 물리적 하한: 도로망 시간은 (출동 준비 + 소방서까지 직선거리 ÷ 최고 속도)보다 짧을 수 없다.
        assertThat(count("""
                SELECT count(*) FROM building b JOIN facility f ON f.id = b.response_station_id
                 WHERE b.response_s + 0.5 < 60 + ST_Distance(b.geom_m, f.geom_m) / (40 / 3.6)""")).isZero();

        JsonNode top = get("/api/v1/buildings/search?sort=risk&limit=1").path("items").path(0);
        JsonNode response = get("/api/v1/buildings/" + top.path("id").asInt()).path("response");
        assertThat(response.path("method").asString()).contains("도로망");
        assertThat(response.path("estimatedMinutes").asDouble()).isGreaterThan(1.0);
        assertThat(response.path("straightLineMinutes").asDouble()).isPositive();
    }

    @Test
    void responseAnalysisPartitionsAllBuildings() {
        JsonNode r = post("/api/v1/analysis/response", Map.of("targetMin", 5));
        JsonNode s = r.path("summary");
        int total = s.path("totalBuildings").asInt();
        assertThat(total).isEqualTo(count("SELECT count(*) FROM building"));
        assertThat(s.path("withinTarget").asInt() + s.path("beyondTarget").asInt() + s.path("unreachable").asInt())
                .isEqualTo(total);
        assertThat(r.path("beyondTargetIds").size()).isEqualTo(s.path("beyondTarget").asInt());
        int regionTotal = 0;
        for (JsonNode region : r.path("regions")) {
            regionTotal += region.path("total").asInt();
        }
        assertThat(regionTotal).isEqualTo(total);
        assertThat(s.path("p90Min").asDouble()).isGreaterThanOrEqualTo(s.path("p50Min").asDouble());
        assertThat(r.path("method").asString()).contains("Dijkstra");
        for (JsonNode slow : r.path("slowest")) {
            assertThat(slow.path("responseMin").asDouble()).isGreaterThanOrEqualTo(5);   // 0.1분 단위 반올림
        }
    }

    @Test
    void isochronesGrowWithTimeAndMatchBuildingTimes() {
        JsonNode fc = mapper.readTree(http.get().uri("/api/v1/analysis/isochrones?minutes=7,3,5")
                .retrieve().body(String.class));
        assertThat(fc.path("features").size()).isEqualTo(3);
        double prev = 0;
        for (JsonNode f : fc.path("features")) {
            double area = area(f.path("geometry").toString());
            assertThat(area).isGreaterThan(prev);
            prev = area;
        }
        assertThat(fc.path("features").path(0).path("properties").path("minutes").asDouble()).isEqualTo(3);

        // 7분 도달권 안 건물의 대부분은 실제 출동 시간이 7분 이하다(도로 양옆 buffer 근사 오차 허용).
        String seven = fc.path("features").path(2).path("geometry").toString();
        double agree = jdbc.sql("""
                        SELECT avg(CASE WHEN b.response_s <= 420 THEN 1.0 ELSE 0 END)
                          FROM building b
                         WHERE ST_Contains(ST_GeomFromGeoJSON(:g), ST_PointOnSurface(b.geom))""")
                .param("g", seven).query(Double.class).single();
        assertThat(agree).isGreaterThan(0.9);
        assertThat(status(http, "GET", "/api/v1/analysis/isochrones?minutes=0.5", null)).isEqualTo(400);
        assertThat(status(http, "GET", "/api/v1/analysis/isochrones?minutes=a,b", null)).isEqualTo(400);
    }

    @Test
    void newStationShortensResponseAndRoadChangesRebuildGraph() {
        double p90Before = post("/api/v1/analysis/response", Map.of()).path("summary").path("p90Min").asDouble();
        long versionBefore = get("/api/v1/analysis/network").path("version").asLong();
        Integer stationId = jdbc.sql("""
                        INSERT INTO facility (type, subtype, name, status, geom)
                        VALUES ('FIRE_STATION', 'SAFETY_CENTER', '테스트 안전센터', 'ACTIVE',
                                (SELECT ST_Centroid(ST_Collect(geom)) FROM region))
                        RETURNING id""").query(Integer.class).single();
        jdbc.sql("UPDATE road SET width_m = width_m + 0.5 WHERE id = (SELECT min(id) FROM road)").update();
        try {
            runRefresh();
            JsonNode state = get("/api/v1/analysis/network");
            assertThat(state.path("version").asLong()).isGreaterThan(versionBefore);
            assertThat(state.path("stale").asBoolean()).isFalse();
            double p90After = post("/api/v1/analysis/response", Map.of()).path("summary").path("p90Min").asDouble();
            assertThat(p90After).isLessThan(p90Before);
            assertThat(count("SELECT count(*) FROM building WHERE response_station_id = " + stationId)).isPositive();
        } finally {
            jdbc.sql("UPDATE building SET response_station_id = NULL WHERE response_station_id = :id")
                    .param("id", stationId).update();
            jdbc.sql("DELETE FROM facility WHERE id = :id").param("id", stationId).update();
            jdbc.sql("UPDATE road SET width_m = width_m - 0.5 WHERE id = (SELECT min(id) FROM road)").update();
            runRefresh();
        }
        assertThat(post("/api/v1/analysis/response", Map.of()).path("summary").path("p90Min").asDouble())
                .isEqualTo(p90Before);
    }

    private void runRefresh() {
        String id = post("/api/v1/analysis/jobs", Map.of("type", "REFRESH_RISK")).path("id").asString();
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            String status = get("/api/v1/analysis/jobs/" + id).path("status").asString();
            assertThat(status).isNotEqualTo("FAILED");
            return status.equals("SUCCEEDED");
        });
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private double area(String geojson) {
        return jdbc.sql("SELECT ST_Area(ST_Transform(ST_GeomFromGeoJSON(:g), 5186))").param("g", geojson)
                .query(Double.class).single();
    }
}
