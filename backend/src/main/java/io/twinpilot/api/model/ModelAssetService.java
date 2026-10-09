package io.twinpilot.api.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonRawValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.job.JobHandler;
import io.twinpilot.api.job.JobQueue;
import io.twinpilot.api.job.Progress;
import io.twinpilot.api.model.storage.ObjectStorage;
import io.twinpilot.api.security.Actor;
import io.twinpilot.api.security.Role;

/**
 * 3D 모델 업로드 파이프라인.
 * 업로드 요청: 파일을 저장소에 스트림으로 저장하고 PROCESSING으로 등록한 뒤 MODEL_PROCESS Job을 넣는다.
 * Job: 저장소에서 읽어 glTF 검증·메타 추출 → READY, 실패하면 파일을 지우고 FAILED.
 * Job 큐를 쓰므로 처리 중 노드가 내려가도 다른 레플리카가 이어서 처리한다.
 */
@Service
public class ModelAssetService {

    private static final Logger log = LoggerFactory.getLogger(ModelAssetService.class);
    static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final String JOB_TYPE = "MODEL_PROCESS";

    public record ModelAsset(UUID id, String name, String status, String error, String format, String originalFilename,
                             Long sizeBytes, @JsonRawValue String stats, Double lon, Double lat, double heightM,
                             double headingDeg, double scale, String createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record Placement(double lon, double lat, double heightM, double headingDeg, double scale) {
    }

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final GltfInspector inspector;
    private final ObjectMapper mapper;
    private final JobQueue jobs;

    public ModelAssetService(JdbcClient jdbc, ObjectStorage storage, GltfInspector inspector, ObjectMapper mapper,
                             JobQueue jobs) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.inspector = inspector;
        this.mapper = mapper;
        this.jobs = jobs;
    }

    public ModelAsset upload(String name, MultipartFile file, Placement placement, Actor actor) {
        String filename = file.getOriginalFilename();
        String lower = filename == null ? "" : filename.toLowerCase();
        if (!lower.endsWith(".glb") && !lower.endsWith(".gltf")) {
            throw new IllegalArgumentException(".glb 또는 .gltf 파일만 업로드할 수 있습니다.");
        }
        if (file.getSize() == 0 || file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("파일 크기는 1B~100MB여야 합니다.");
        }
        UUID id = UUID.randomUUID();
        String format = lower.endsWith(".glb") ? "glb" : "gltf";
        String key = "models/%s.%s".formatted(id, format);
        String safeName = filename.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), format.equals("glb") ? "model/gltf-binary" : "model/gltf+json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        jdbc.sql("""
                        INSERT INTO model_asset (id, name, status, format, original_filename, size_bytes, storage_key,
                                                 lon, lat, height_m, heading_deg, scale, created_by)
                        VALUES (:id, :name, 'PROCESSING', :format, :filename, :size, :key, :lon, :lat, :h, :heading,
                                :scale, :by)""")
                .param("id", id)
                .param("name", name == null || name.isBlank() ? safeName : name)
                .param("format", format)
                .param("filename", safeName)
                .param("size", file.getSize())
                .param("key", key)
                .param("lon", placement == null ? null : placement.lon())
                .param("lat", placement == null ? null : placement.lat())
                .param("h", placement == null ? 0 : placement.heightM())
                .param("heading", placement == null ? 0 : placement.headingDeg())
                .param("scale", placement == null ? 1 : placement.scale())
                .param("by", actor.username())
                .update();
        jobs.submit(JOB_TYPE, Map.of("modelId", id.toString()), actor, "model:" + id);
        return get(id);
    }

    /** MODEL_PROCESS Job 본문. */
    Object process(UUID id, Progress progress) {
        String key = storageKey(id);
        ModelAsset asset = get(id);
        progress.report(20, "glTF 검증");
        try {
            byte[] data;
            try (InputStream in = storage.get(key)) {
                data = in.readAllBytes();
            }
            var inspection = inspector.inspect(data, asset.originalFilename());
            Map<String, Object> stats = new LinkedHashMap<>(inspection.stats());
            stats.put("warnings", inspection.warnings());
            stats.put("storage", storage.kind());
            jdbc.sql("""
                            UPDATE model_asset SET status = 'READY', stats = CAST(:stats AS jsonb),
                                   content_sha256 = :sha, updated_at = now() WHERE id = :id""")
                    .param("stats", mapper.writeValueAsString(stats))
                    .param("sha", sha256(data))
                    .param("id", id)
                    .update();
            return Map.of("modelId", id.toString(), "status", "READY", "stats", stats);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            log.info("모델 {} 검증 실패: {}", id, e.getMessage());
            jdbc.sql("UPDATE model_asset SET status = 'FAILED', error = :error, updated_at = now() WHERE id = :id")
                    .param("error", e.getMessage())
                    .param("id", id)
                    .update();
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // 메타데이터에 실패가 남으므로 파일 정리 실패는 무시한다.
            }
            return Map.of("modelId", id.toString(), "status", "FAILED", "error", e.getMessage());
        }
    }

    public List<ModelAsset> list() {
        return jdbc.sql(SELECT + " ORDER BY created_at DESC").query(this::row).list();
    }

    public ModelAsset get(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(this::row).optional()
                .orElseThrow(() -> new NoSuchElementException("모델을 찾을 수 없습니다: " + id));
    }

    public ModelAsset place(UUID id, Placement p) {
        int n = jdbc.sql("""
                        UPDATE model_asset SET lon = :lon, lat = :lat, height_m = :h, heading_deg = :heading,
                               scale = :scale, updated_at = now() WHERE id = :id""")
                .param("lon", p.lon()).param("lat", p.lat()).param("h", p.heightM())
                .param("heading", p.headingDeg()).param("scale", p.scale()).param("id", id)
                .update();
        if (n == 0) {
            throw new NoSuchElementException("모델을 찾을 수 없습니다: " + id);
        }
        return get(id);
    }

    public InputStream open(UUID id) {
        ModelAsset m = get(id);
        if (!"READY".equals(m.status())) {
            throw new IllegalStateException("모델이 아직 준비되지 않았습니다: " + m.status());
        }
        return storage.get(storageKey(id));
    }

    public String etag(UUID id) {
        return jdbc.sql("SELECT content_sha256 FROM model_asset WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    public void delete(UUID id) {
        String key = storageKey(id);
        jdbc.sql("DELETE FROM model_asset WHERE id = :id").param("id", id).update();
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("저장소 파일 삭제 실패(메타데이터는 삭제됨): {}", key, e);
        }
    }

    private String storageKey(UUID id) {
        return jdbc.sql("SELECT storage_key FROM model_asset WHERE id = :id").param("id", id)
                .query(String.class).optional()
                .orElseThrow(() -> new NoSuchElementException("모델을 찾을 수 없습니다: " + id));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SELECT = """
            SELECT id, name, status, error, format, original_filename, size_bytes, stats::text AS stats, lon, lat,
                   height_m, heading_deg, scale, created_by, created_at, updated_at FROM model_asset""";

    private ModelAsset row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ModelAsset(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getString("error"), rs.getString("format"), rs.getString("original_filename"),
                (Long) rs.getObject("size_bytes"), rs.getString("stats"),
                (Double) rs.getObject("lon"), (Double) rs.getObject("lat"), rs.getDouble("height_m"),
                rs.getDouble("heading_deg"), rs.getDouble("scale"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    @Configuration
    static class Handlers {

        @Bean
        JobHandler modelProcessHandler(org.springframework.beans.factory.ObjectProvider<ModelAssetService> service) {
            return new JobHandler() {
                @Override
                public String type() {
                    return JOB_TYPE;
                }

                @Override
                public Role requiredRole() {
                    return Role.PLANNER;
                }

                @Override
                public boolean countsTowardQuota() {
                    return false;
                }

                @Override
                public Object run(JsonNode params, Actor actor, Progress progress) {
                    return service.getObject().process(UUID.fromString(params.path("modelId").asString()), progress);
                }
            };
        }
    }
}
