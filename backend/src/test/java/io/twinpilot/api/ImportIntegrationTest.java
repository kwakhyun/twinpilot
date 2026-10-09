package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.support.IntegrationTestBase;

/** GeoJSON 적재 파이프라인: 매핑·검증·좌표계 변환·지오메트리 보정·upsert·REPLACE. */
class ImportIntegrationTest extends IntegrationTestBase {

    private static final String MAPPING = """
            {"fields": {"externalId": "BLD_ID", "name": "BLD_NM", "useType": "MAIN_USE", "structure": "STRCT",
                        "floors": "GRND_FLR", "builtYear": "USE_APRV_DE"},
             "values": {"useType": {"공동주택": "RESIDENTIAL_APT", "제2종근린생활시설": "COMMERCIAL"},
                        "structure": {"철근콘크리트구조": "RC", "벽돌구조": "MASONRY"}},
             "floorHeightM": 3.0}""";

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM building WHERE source = 'test-bld'").update();
        jdbc.sql("UPDATE building SET response_station_id = NULL WHERE response_station_id IN "
                + "(SELECT id FROM facility WHERE source = 'test-hyd')").update();
        jdbc.sql("DELETE FROM facility WHERE source = 'test-hyd'").update();
    }

    @Test
    void buildingsInKoreanCrsAreValidatedMappedAndUpserted() {
        double[] c = toEpsg5186(129.0600, 35.1600);   // 합성 도시 안쪽
        String first = featureCollection(5186,
                building("B-1", "가람아파트", "공동주택", "철근콘크리트구조", "15", "19950312", square(c[0], c[1], 20)),
                building("B-2", "꼬인 건물", "제2종근린생활시설", "벽돌구조", "3", "2001-05-01",
                        bowtie(c[0] + 60, c[1], 15)),
                building("B-3", "알 수 없음", "우주정거장", "철근콘크리트구조", "2", "2010", square(c[0] + 120, c[1], 10)),
                "{\"type\":\"Feature\",\"properties\":{\"BLD_ID\":\"B-4\"},\"geometry\":null}",
                building("B-5", "엉뚱한 좌표", "공동주택", "철근콘크리트구조", "5", "2000", square(10, 10, 10)));

        JsonNode dry = runImport("BUILDING", "test-bld", null, "UPSERT", true, first);
        assertThat(dry.path("srid").asInt()).isEqualTo(5186);   // 파일의 crs에서 읽음
        assertThat(dry.path("total").asInt()).isEqualTo(5);
        assertThat(dry.path("valid").asInt()).isEqualTo(2);
        assertThat(dry.path("invalid").asInt()).isEqualTo(3);
        assertThat(dry.path("outOfBounds").asInt()).isEqualTo(1);
        assertThat(dry.path("fixedGeometries").asInt()).isEqualTo(1);
        assertThat(dry.path("inserted").asInt()).isZero();
        assertThat(dry.path("errors").toString()).contains("우주정거장").contains("지오메트리가 없습니다");
        assertThat(dry.path("sample").path(0).path("useType").asString()).isEqualTo("RESIDENTIAL_APT");
        assertThat(dry.path("sample").path(0).path("builtYear").asInt()).isEqualTo(1995);
        assertThat(count("SELECT count(*) FROM building WHERE source = 'test-bld'")).isZero();

        JsonNode real = runImport("BUILDING", "test-bld", null, "UPSERT", false, first);
        assertThat(real.path("inserted").asInt()).isEqualTo(2);
        assertThat(real.path("riskRefresh").path("updatedBuildings").asInt()).isGreaterThan(2000);
        Map<String, Object> b1 = jdbc.sql("""
                        SELECT use_type, structure, floors, height_m, built_year, region_id, risk_score, response_s,
                               ST_IsValid(geom) AS valid
                          FROM building WHERE source = 'test-bld' AND external_id = 'B-1'""").query().singleRow();
        assertThat(b1.get("use_type")).isEqualTo("RESIDENTIAL_APT");
        assertThat(b1.get("floors")).isEqualTo(15);
        assertThat((Double) b1.get("height_m")).isEqualTo(45.0);   // 높이가 없으면 층수 × 층고
        assertThat(b1.get("region_id")).isNotNull();
        assertThat(b1.get("risk_score")).isNotNull();
        assertThat(b1.get("response_s")).isNotNull();
        assertThat(count("SELECT count(*) FROM building WHERE source = 'test-bld' AND NOT ST_IsValid(geom)")).isZero();

        // 같은 출처·식별자는 갱신된다.
        String second = featureCollection(5186,
                building("B-1", "가람아파트", "공동주택", "철근콘크리트구조", "20", "19950312", square(c[0], c[1], 20)),
                building("B-2", "꼬인 건물", "제2종근린생활시설", "벽돌구조", "4", "2001-05-01", square(c[0] + 60, c[1], 15)));
        JsonNode updated = runImport("BUILDING", "test-bld", null, "UPSERT", false, second);
        assertThat(updated.path("inserted").asInt()).isZero();
        assertThat(updated.path("updated").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT floors FROM building WHERE source = 'test-bld' AND external_id = 'B-1'")
                .query(Integer.class).single()).isEqualTo(20);

        // REPLACE: 파일에 없는 같은 출처 건물은 지운다.
        JsonNode replaced = runImport("BUILDING", "test-bld", null, "REPLACE", false, featureCollection(5186,
                building("B-1", "가람아파트", "공동주택", "철근콘크리트구조", "20", "19950312", square(c[0], c[1], 20))));
        assertThat(replaced.path("removedOrDeactivated").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM building WHERE source = 'test-bld'")).isEqualTo(1);

        assertThat(get("/api/v1/admin/audit?action=DATA_IMPORT").path(0).path("targetId").asString()).isEqualTo("test-bld");
        assertThat(get("/api/v1/admin/imports").size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void facilitiesReplaceDeactivatesMissingOnes() {
        String two = """
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","id":"H-1","properties":{"name":"시험 소화전 1","subtype":"지상식"},
                  "geometry":{"type":"Point","coordinates":[129.0570,35.1590]}},
                 {"type":"Feature","id":"H-2","properties":{"name":"시험 소화전 2","installedYear":"2015"},
                  "geometry":{"type":"Point","coordinates":[129.0580,35.1592]}}]}""";
        JsonNode first = runImport("HYDRANT", "test-hyd", 4326, "UPSERT", false, two, false);
        assertThat(first.path("inserted").asInt()).isEqualTo(2);
        assertThat(first.path("riskRefresh").isNull()).isTrue();
        String one = """
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","id":"H-1","properties":{"name":"시험 소화전 1","subtype":"지상식"},
                  "geometry":{"type":"Point","coordinates":[129.0570,35.1590]}}]}""";
        JsonNode replaced = runImport("HYDRANT", "test-hyd", 4326, "REPLACE", false, one, false);
        assertThat(replaced.path("removedOrDeactivated").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM facility WHERE source = 'test-hyd' AND external_id = 'H-2'")
                .query(String.class).single()).isEqualTo("INACTIVE");
    }

    @Test
    void requestValidationAndAuthorization() {
        assertThat(upload(http, "TOWER", "x", "{}")).isEqualTo(400);
        assertThat(upload(http, "BUILDING", "Bad Source!", "{}")).isEqualTo(400);
        assertThat(uploadWithMapping(http, "{not json")).isEqualTo(400);
        assertThat(uploadWithMapping(http, "{\"fields\":{\"color\":\"X\"}}")).isEqualTo(400);
        assertThat(upload(as("planner", "PLANNER"), "BUILDING", "ok", "{}")).isEqualTo(403);
        // 깨진 JSON 파일은 Job 실패로 보고된다.
        String id = mapper.readTree(multipart(http, "BUILDING", "broken", "{\"type\":\"FeatureCollection\",\"features\":[{",
                null, false, null, true)).path("id").asString();
        await().atMost(Duration.ofSeconds(20)).until(() -> get("/api/v1/analysis/jobs/" + id).path("status").asString()
                .equals("FAILED"));
        assertThat(get("/api/v1/analysis/jobs/" + id).path("error").asString()).contains("GeoJSON");
    }

    private JsonNode runImport(String layer, String source, Integer srid, String mode, boolean dryRun, String geojson) {
        return runImport(layer, source, srid, mode, dryRun, geojson, true);
    }

    private JsonNode runImport(String layer, String source, Integer srid, String mode, boolean dryRun, String geojson,
                               boolean refresh) {
        String body = multipart(http, layer, source, geojson, srid, dryRun, mode, refresh);
        String id = mapper.readTree(body).path("id").asString();
        await().atMost(Duration.ofSeconds(90)).until(() -> {
            String status = get("/api/v1/analysis/jobs/" + id).path("status").asString();
            assertThat(status).isNotEqualTo("FAILED");
            return status.equals("SUCCEEDED");
        });
        return get("/api/v1/analysis/jobs/" + id).path("result");
    }

    private String multipart(RestClient client, String layer, String source, String geojson, Integer srid,
                             boolean dryRun, String mode, boolean refresh) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(geojson.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "data.geojson";
            }
        });
        body.part("layer", layer);
        body.part("source", source);
        body.part("mapping", MAPPING);
        body.part("dryRun", Boolean.toString(dryRun));
        body.part("refreshRisk", Boolean.toString(refresh));
        if (mode != null) {
            body.part("mode", mode);
        }
        if (srid != null) {
            body.part("srid", srid.toString());
        }
        return client.post().uri("/api/v1/admin/imports").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).retrieve().body(String.class);
    }

    private int upload(RestClient client, String layer, String source, String geojson) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(geojson.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "x.geojson";
            }
        });
        body.part("layer", layer);
        body.part("source", source);
        body.part("dryRun", "true");
        return client.post().uri("/api/v1/admin/imports").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).exchange((req, res) -> res.getStatusCode().value());
    }

    private int uploadWithMapping(RestClient client, String mapping) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "x.geojson";
            }
        });
        body.part("layer", "BUILDING");
        body.part("source", "ok");
        body.part("mapping", mapping);
        return client.post().uri("/api/v1/admin/imports").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).exchange((req, res) -> res.getStatusCode().value());
    }

    private double[] toEpsg5186(double lon, double lat) {
        return jdbc.sql("""
                        SELECT ST_X(p), ST_Y(p) FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), 5186) p) t""")
                .param("lon", lon).param("lat", lat)
                .query((rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)}).single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private static String featureCollection(int srid, String... features) {
        return "{\"type\":\"FeatureCollection\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:EPSG::"
                + srid + "\"}},\"features\":[" + String.join(",", features) + "]}";
    }

    private static String building(String id, String name, String use, String structure, String floors, String approved,
                                   String geometry) {
        return """
                {"type":"Feature","properties":{"BLD_ID":"%s","BLD_NM":"%s","MAIN_USE":"%s","STRCT":"%s",
                 "GRND_FLR":"%s","USE_APRV_DE":"%s"},"geometry":%s}"""
                .formatted(id, name, use, structure, floors, approved, geometry);
    }

    private static String square(double x, double y, double half) {
        List<double[]> ring = new ArrayList<>(List.of(new double[]{x - half, y - half}, new double[]{x + half, y - half},
                new double[]{x + half, y + half}, new double[]{x - half, y + half}, new double[]{x - half, y - half}));
        return polygon(ring);
    }

    /** 자기 교차(나비 모양) 폴리곤 — ST_MakeValid로 보정돼야 한다. */
    private static String bowtie(double x, double y, double half) {
        return polygon(List.of(new double[]{x - half, y - half}, new double[]{x + half, y + half},
                new double[]{x + half, y - half}, new double[]{x - half, y + half}, new double[]{x - half, y - half}));
    }

    private static String polygon(List<double[]> ring) {
        StringBuilder sb = new StringBuilder("{\"type\":\"Polygon\",\"coordinates\":[[");
        for (int i = 0; i < ring.size(); i++) {
            sb.append(i == 0 ? "" : ",").append("[").append(ring.get(i)[0]).append(",").append(ring.get(i)[1]).append("]");
        }
        return sb.append("]]}").toString();
    }
}
