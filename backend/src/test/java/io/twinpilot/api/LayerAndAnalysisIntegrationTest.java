package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.support.IntegrationTestBase;

/** 레이어(GeoJSON)·검색·상세·커버리지·통계 시나리오. */
class LayerAndAnalysisIntegrationTest extends IntegrationTestBase {

    @Test
    void layersAreGeoJson() {
        JsonNode regions = get("/api/v1/regions");
        assertThat(regions.path("type").asString()).isEqualTo("FeatureCollection");
        assertThat(regions.path("features").size()).isEqualTo(4);
        assertThat(regions.path("features").path(0).path("properties").path("hydrantRadiusM").asDouble()).isEqualTo(100);

        var buildings = http.get().uri("/api/v1/buildings?regionId=3").retrieve().toEntity(String.class);
        assertThat(buildings.getHeaders().getContentType().toString()).startsWith("application/geo+json");
        assertThat(buildings.getHeaders().getETag()).isNotBlank();
        // 인증 응답이지만 ETag 재검증이 가능해야 한다(no-store 금지).
        assertThat(buildings.getHeaders().getCacheControl()).contains("no-cache").doesNotContain("no-store");
        HttpStatusCode notModified = http.get().uri("/api/v1/buildings?regionId=3")
                .header("If-None-Match", buildings.getHeaders().getETag())
                .exchange((req, res) -> res.getStatusCode());
        assertThat(notModified).isEqualTo(HttpStatus.NOT_MODIFIED);
        JsonNode fc = mapper.readTree(buildings.getBody());
        assertThat(fc.path("features").size()).isGreaterThan(100);
        JsonNode first = fc.path("features").path(0);
        assertThat(first.path("geometry").path("type").asString()).isEqualTo("MultiPolygon");
        assertThat(first.path("properties").path("risk").isNumber()).isTrue();
    }

    @Test
    void searchFiltersAndSortsByRisk() {
        JsonNode result = get("/api/v1/buildings/search?regionId=3&minAge=30&uncoveredBy=HYDRANT&limit=5");
        assertThat(result.path("total").asLong()).isPositive();
        assertThat(result.path("items").size()).isLessThanOrEqualTo(5);
        double prev = Double.MAX_VALUE;
        for (JsonNode item : result.path("items")) {
            assertThat(item.path("age").asInt()).isGreaterThanOrEqualTo(30);
            assertThat(item.path("regionName").asString()).isEqualTo("다솜동");
            assertThat(item.path("risk").asDouble()).isLessThanOrEqualTo(prev);
            assertThat(item.path("riskFactors").path("nearestHydrantM").asDouble()).isGreaterThan(100);
            prev = item.path("risk").asDouble();
        }
    }

    @Test
    void buildingDetailCombinesParallelQueries() {
        JsonNode top = get("/api/v1/buildings/search?sort=floors&limit=1").path("items").path(0);
        JsonNode detail = get("/api/v1/buildings/" + top.path("id").asInt());

        assertThat(detail.path("building").path("floors").asInt()).isGreaterThan(15);
        assertThat(detail.path("nearestHydrants").size()).isEqualTo(3);
        assertThat(detail.path("nearestHydrants").path(0).path("distanceM").asDouble())
                .isLessThanOrEqualTo(detail.path("nearestHydrants").path(1).path("distanceM").asDouble());
        assertThat(detail.path("response").path("stationName").asString()).isNotBlank();
        assertThat(detail.path("ladder").path("floorsBeyondReach").asInt()).isPositive();
        assertThat(detail.path("footprint").path("type").asString()).isEqualTo("MultiPolygon");

        HttpStatusCode missing = http.get().uri("/api/v1/buildings/999999").exchange((req, res) -> res.getStatusCode());
        assertThat(missing).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void coverageMatchesBuildingSearch() {
        JsonNode cov = post("/api/v1/analysis/coverage", Map.of("facilityType", "HYDRANT", "regionId", 3));
        JsonNode summary = cov.path("summary");
        int uncovered = summary.path("totalBuildings").asInt() - summary.path("coveredBuildings").asInt();

        assertThat(summary.path("coverageRate").asDouble()).isBetween(1.0, 99.0);
        assertThat(cov.path("uncoveredBuildingIds").size()).isEqualTo(uncovered);
        assertThat(cov.path("blindSpots").size()).isEqualTo(1);
        assertThat(cov.path("blindSpots").path(0).path("geometry").path("type").asString()).isEqualTo("MultiPolygon");
        assertThat(cov.path("standard").asString()).contains("별표 3");

        // 같은 기준을 쓰는 건물 검색 결과와 미커버 건수가 일치해야 한다.
        JsonNode search = get("/api/v1/buildings/search?regionId=3&uncoveredBy=HYDRANT&limit=1");
        assertThat(search.path("total").asInt()).isEqualTo(uncovered);

        JsonNode wider = post("/api/v1/analysis/coverage",
                Map.of("facilityType", "HYDRANT", "regionId", 3, "radiusM", 300, "includeBlindSpots", false));
        assertThat(wider.path("summary").path("coverageRate").asDouble())
                .isGreaterThan(summary.path("coverageRate").asDouble());
    }

    @Test
    void invalidRequestsReturnProblemDetail() {
        HttpStatusCode status = http.post().uri("/api/v1/analysis/coverage").contentType(MediaType.APPLICATION_JSON)
                .body("{\"facilityType\":\"BOMB\"}").exchange((req, res) -> res.getStatusCode());
        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
        HttpStatusCode sort = http.get().uri("/api/v1/buildings/search?sort=drop").exchange((req, res) -> res.getStatusCode());
        assertThat(sort).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void statsSummary() {
        JsonNode s = get("/api/v1/stats/summary");
        assertThat(s.path("totals").path("buildings").asInt()).isGreaterThan(2000);
        assertThat(s.path("regions").size()).isEqualTo(4);
        assertThat(s.path("hydrantCoverage").path("coverageRate").asDouble()).isBetween(1.0, 100.0);
        assertThat(s.path("riskHistogram").size()).isGreaterThan(3);
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void analysisCachesFollowDataChanges() throws InterruptedException {
        int before = plannedHydrants();
        Map<String, Object> withPlanned = Map.of("facilityType", "HYDRANT", "includePlanned", true,
                "includeBlindSpots", false, "topUncovered", 1);
        JsonNode coverageBefore = post("/api/v1/analysis/coverage", withPlanned);
        int coveredBefore = coverageBefore.path("summary").path("coveredBuildings").asInt();
        // 소화전이 닿지 않는 건물 자리에 계획 소화전을 두면 그 건물이 커버된다.
        JsonNode site = coverageBefore.path("topUncovered").path(0);
        JsonNode created = post("/api/v1/facilities",
                Map.of("type", "HYDRANT", "lon", site.path("lon").asDouble(), "lat", site.path("lat").asDouble()));
        // 같은 레플리카에서 쓴 직후에는 캐시(통계·커버리지)가 바로 비워져 새 값이 보인다.
        assertThat(plannedHydrants()).isEqualTo(before + 1);
        assertThat(post("/api/v1/analysis/coverage", withPlanned).path("summary").path("coveredBuildings").asInt())
                .isGreaterThan(coveredBefore);

        // 다른 레플리카가 쓴 경우를 흉내 낸다: DB만 바꾸고 NOTIFY를 보내면 이 레플리카의 캐시도 비워진다(TTL 15초보다 빨리).
        jdbc.sql("DELETE FROM facility WHERE id = :id").param("id", created.path("id").asInt()).update();
        jdbc.sql("SELECT pg_notify('twinpilot_data_changed', 'test')").query(Object.class).optional();
        long deadline = System.currentTimeMillis() + 5_000;
        while (plannedHydrants() != before && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(plannedHydrants()).isEqualTo(before);
    }

    @Test
    void statsRejectsUnboundedCacheKeys() {
        HttpStatusCode status = http.get().uri("/api/v1/stats/summary?oldAgeYears=100000")
                .exchange((req, res) -> res.getStatusCode());
        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private int plannedHydrants() {
        for (JsonNode bucket : get("/api/v1/stats/summary").path("facilities")) {
            if ("HYDRANT:PLANNED".equals(bucket.path("key").asString())) {
                return bucket.path("count").asInt();
            }
        }
        return 0;
    }
}
