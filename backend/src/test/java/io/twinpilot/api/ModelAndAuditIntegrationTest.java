package io.twinpilot.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import io.twinpilot.api.support.IntegrationTestBase;

/** 3D 모델 업로드(작업 큐 처리)와 감사 로그. */
class ModelAndAuditIntegrationTest extends IntegrationTestBase {

    @Test
    void modelUploadIsProcessedByTheJobQueue() throws Exception {
        RestClient planner = as("modeler", "PLANNER");
        byte[] glb = Files.readAllBytes(Path.of("../data/sample-models/landmark-tower.glb"));
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", named(glb, "landmark-tower.glb"));
        body.part("name", "랜드마크 타워");
        body.part("lon", "129.0575");
        body.part("lat", "35.1605");
        var created = planner.post().uri("/api/v1/models").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build()).retrieve().toEntity(String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = mapper.readTree(created.getBody()).path("id").asString();

        await().atMost(Duration.ofSeconds(15))
                .until(() -> get("/api/v1/models/" + id).path("status").asString().equals("READY"));
        JsonNode model = get("/api/v1/models/" + id);
        assertThat(model.path("stats").path("triangles").asInt()).isEqualTo(60);
        assertThat(model.path("lon").asDouble()).isEqualTo(129.0575);
        assertThat(model.path("createdBy").asString()).isEqualTo("modeler");

        var file = http.get().uri("/api/v1/models/" + id + "/file").retrieve().toEntity(byte[].class);
        assertThat(file.getBody()).isEqualTo(glb);
        assertThat(file.getHeaders().getETag()).isNotBlank();
        int revalidated = http.get().uri("/api/v1/models/" + id + "/file").header("If-None-Match", file.getHeaders().getETag())
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(revalidated).isEqualTo(304);

        JsonNode placed = mapper.readTree(planner.put().uri("/api/v1/models/" + id + "/placement")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"lon\":129.058,\"lat\":35.161,\"heightM\":0,\"headingDeg\":30}")
                .retrieve().body(String.class));
        assertThat(placed.path("headingDeg").asDouble()).isEqualTo(30);
        assertThat(status(planner, "PUT", "/api/v1/models/" + id + "/placement",
                Map.of("lon", 129.0, "lat", 35.1, "heightM", 0, "headingDeg", 0, "scale", 5000))).isEqualTo(400);

        MultipartBodyBuilder badBody = new MultipartBodyBuilder();
        badBody.part("file", named("not a model".getBytes(StandardCharsets.UTF_8), "fake.glb"));
        var bad = planner.post().uri("/api/v1/models").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(badBody.build()).retrieve().toEntity(String.class);
        String badId = mapper.readTree(bad.getBody()).path("id").asString();
        await().atMost(Duration.ofSeconds(15))
                .until(() -> get("/api/v1/models/" + badId).path("status").asString().equals("FAILED"));

        // 감사 로그에 주체와 대상이 남는다.
        JsonNode audit = get("/api/v1/admin/audit?actor=modeler");
        assertThat(audit.findValuesAsString("action")).contains("MODEL_UPLOAD", "MODEL_PLACE");
        assertThat(audit.path(0).path("actorName").asString()).isEqualTo("modeler");

        assertThat(status(planner, "DELETE", "/api/v1/models/" + badId, null)).isEqualTo(204);
        assertThat(get("/api/v1/admin/audit?action=MODEL_DELETE").findValuesAsString("targetId")).contains(badId);
    }

    @Test
    void plannedFacilityChangesAreAudited() {
        RestClient planner = as("planner-audit", "PLANNER");
        JsonNode created = mapper.readTree(planner.post().uri("/api/v1/facilities").contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("type", "HYDRANT", "lon", 129.056, "lat", 35.158)))
                .retrieve().body(String.class));
        String id = created.path("id").asString();
        assertThat(status(planner, "DELETE", "/api/v1/facilities/" + id, null)).isEqualTo(204);
        JsonNode audit = get("/api/v1/admin/audit?actor=planner-audit");
        assertThat(audit.findValuesAsString("action")).containsSequence("FACILITY_PLAN_DELETE", "FACILITY_PLAN");
        assertThat(audit.path(1).path("details").path("type").asString()).isEqualTo("HYDRANT");
    }

    private static ByteArrayResource named(byte[] data, String filename) {
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
