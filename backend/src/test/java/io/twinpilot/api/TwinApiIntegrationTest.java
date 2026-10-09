package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실제 PostGIS 컨테이너에 합성 데이터를 적재하고 HTTP로 주요 시나리오를 검증한다. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TwinApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgis = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("twinpilot.storage.local-root", () -> "target/test-storage");
        registry.add("twinpilot.sensors.interval-ms", () -> "200");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.create("http://localhost:" + port);
    }

    private JsonNode get(String path) {
        return mapper.readTree(http.get().uri(path).retrieve().body(String.class));
    }

    private JsonNode post(String path, Object body) {
        return mapper.readTree(http.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body)).retrieve().body(String.class));
    }

    @Test
    void layersAreGeoJson() {
        JsonNode regions = get("/api/v1/regions");
        assertThat(regions.path("type").asString()).isEqualTo("FeatureCollection");
        assertThat(regions.path("features").size()).isEqualTo(4);
        assertThat(regions.path("features").path(0).path("properties").path("hydrantRadiusM").asDouble()).isEqualTo(100);

        var buildings = http.get().uri("/api/v1/buildings?regionId=3").retrieve().toEntity(String.class);
        assertThat(buildings.getHeaders().getContentType().toString()).startsWith("application/geo+json");
        assertThat(buildings.getHeaders().getETag()).isNotBlank();
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
    void recommendationJobImprovesCoverageAndStreamsProgress() throws Exception {
        var created = http.post().uri("/api/v1/analysis/jobs").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("type", "RECOMMEND_SITES", "facilityType", "HYDRANT",
                        "count", 3, "regionId", 3)))
                .retrieve().toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = mapper.readTree(created.getBody()).path("id").asString();

        List<String> events = readSse("/api/v1/analysis/jobs/" + id + "/events", "done", Duration.ofSeconds(60));
        assertThat(events).contains("progress").last().isEqualTo("done");

        JsonNode job = get("/api/v1/analysis/jobs/" + id);
        assertThat(job.path("status").asString()).isEqualTo("SUCCEEDED");
        JsonNode result = job.path("result");
        assertThat(result.path("sites").size()).isEqualTo(3);
        assertThat(result.path("coverageAfter").asDouble()).isGreaterThan(result.path("coverageBefore").asDouble());
        for (JsonNode site : result.path("sites")) {
            assertThat(site.path("regionId").asInt()).isEqualTo(3);
            assertThat(site.path("newlyCoveredBuildings").asInt()).isPositive();
        }

        // 추천 1순위를 계획 시설로 배치하면 includePlanned 커버리지가 그만큼 오른다.
        JsonNode site = result.path("sites").path(0);
        JsonNode planned = post("/api/v1/facilities", Map.of("type", "HYDRANT", "lon", site.path("lon").asDouble(),
                "lat", site.path("lat").asDouble()));
        assertThat(planned.path("status").asString()).isEqualTo("PLANNED");
        JsonNode withPlan = post("/api/v1/analysis/coverage",
                Map.of("facilityType", "HYDRANT", "regionId", 3, "includePlanned", true, "includeBlindSpots", false));
        JsonNode withoutPlan = post("/api/v1/analysis/coverage",
                Map.of("facilityType", "HYDRANT", "regionId", 3, "includeBlindSpots", false));
        assertThat(withPlan.path("summary").path("coveredBuildings").asInt()
                - withoutPlan.path("summary").path("coveredBuildings").asInt())
                .isEqualTo(site.path("newlyCoveredBuildings").asInt());
        http.delete().uri("/api/v1/facilities/planned").retrieve().toBodilessEntity();
    }

    @Test
    void sensorsStreamAndAlert() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> get("/api/v1/sensors").path("total").asInt() > 100);
        JsonNode status = get("/api/v1/sensors?kind=AIR_QUALITY");
        assertThat(status.path("readings").size()).isEqualTo(4);
        assertThat(status.path("readings").path(0).path("values").has("pm25")).isTrue();

        List<String> events = readSse("/api/v1/sensors/stream", "readings", Duration.ofSeconds(10));
        assertThat(events).contains("readings");
    }

    @Test
    void statsSummary() {
        JsonNode s = get("/api/v1/stats/summary");
        assertThat(s.path("totals").path("buildings").asInt()).isGreaterThan(2000);
        assertThat(s.path("regions").size()).isEqualTo(4);
        assertThat(s.path("hydrantCoverage").path("coverageRate").asDouble()).isBetween(1.0, 100.0);
        assertThat(s.path("riskHistogram").size()).isGreaterThan(3);
    }

    @Test
    void modelUploadIsProcessedAsynchronously() throws Exception {
        byte[] glb = Files.readAllBytes(Path.of("../data/sample-models/landmark-tower.glb"));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", named(glb, "landmark-tower.glb"));
        body.part("name", "랜드마크 타워");
        body.part("lon", "129.0575");
        body.part("lat", "35.1605");
        var created = http.post().uri("/api/v1/models").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).retrieve().toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = mapper.readTree(created.getBody()).path("id").asString();

        await().atMost(Duration.ofSeconds(10))
                .until(() -> get("/api/v1/models/" + id).path("status").asString().equals("READY"));
        JsonNode model = get("/api/v1/models/" + id);
        assertThat(model.path("stats").path("triangles").asInt()).isEqualTo(60);
        assertThat(model.path("lon").asDouble()).isEqualTo(129.0575);

        byte[] file = http.get().uri("/api/v1/models/" + id + "/file").retrieve().body(byte[].class);
        assertThat(file).isEqualTo(glb);

        JsonNode placed = mapper.readTree(http.put().uri("/api/v1/models/" + id + "/placement")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"lon\":129.058,\"lat\":35.161,\"heightM\":0,\"headingDeg\":30}")
                .retrieve().body(String.class));
        assertThat(placed.path("headingDeg").asDouble()).isEqualTo(30);

        MultipartBodyBuilder badBody = new MultipartBodyBuilder();
        badBody.part("file", named("not a model".getBytes(StandardCharsets.UTF_8), "fake.glb"));
        var bad = http.post().uri("/api/v1/models").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(badBody.build()).retrieve().toEntity(String.class);
        String badId = mapper.readTree(bad.getBody()).path("id").asString();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> get("/api/v1/models/" + badId).path("status").asString().equals("FAILED"));
    }

    @Test
    void actuatorProbesAndPrometheus() {
        assertThat(get("/actuator/health/readiness").path("status").asString()).isEqualTo("UP");
        assertThat(http.get().uri("/actuator/prometheus").retrieve().body(String.class)).contains("jvm_threads");
    }

    private static ByteArrayResource named(byte[] data, String filename) {
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /** SSE를 읽어 이벤트 이름 목록을 돌려준다. {@code until} 이벤트를 받으면 멈춘다. */
    private List<String> readSse(String path, String until, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").timeout(timeout).build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        List<String> events = new ArrayList<>();
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
