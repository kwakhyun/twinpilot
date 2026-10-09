package io.twinpilot.api.model;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonRawValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.model.storage.ObjectStorage;

/**
 * 3D 모델 업로드 파이프라인: 등록(PROCESSING) → [비동기] 저장소 업로드 + glTF 검증·메타 추출 → READY/FAILED.
 */
@Service
public class ModelAssetService {

    private static final Logger log = LoggerFactory.getLogger(ModelAssetService.class);
    static final long MAX_BYTES = 100L * 1024 * 1024;

    public record ModelAsset(UUID id, String name, String status, String error, String format, String originalFilename,
                             Long sizeBytes, @JsonRawValue String stats, Double lon, Double lat, double heightM,
                             double headingDeg, double scale, Instant createdAt, Instant updatedAt) {
    }

    public record Placement(double lon, double lat, double heightM, double headingDeg, double scale) {
    }

    private final JdbcClient jdbc;
    private final ObjectStorage storage;
    private final GltfInspector inspector;
    private final ObjectMapper mapper;
    private final ExecutorService executor;

    public ModelAssetService(JdbcClient jdbc, ObjectStorage storage, GltfInspector inspector, ObjectMapper mapper,
                             ExecutorService virtualThreadExecutor) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.inspector = inspector;
        this.mapper = mapper;
        this.executor = virtualThreadExecutor;
    }

    public ModelAsset upload(String name, String filename, byte[] data, Placement placement) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (!lower.endsWith(".glb") && !lower.endsWith(".gltf")) {
            throw new IllegalArgumentException(".glb 또는 .gltf 파일만 업로드할 수 있습니다.");
        }
        if (data.length == 0 || data.length > MAX_BYTES) {
            throw new IllegalArgumentException("파일 크기는 1B~100MB여야 합니다.");
        }
        UUID id = UUID.randomUUID();
        String format = lower.endsWith(".glb") ? "glb" : "gltf";
        String key = "models/%s.%s".formatted(id, format);
        jdbc.sql("""
                        INSERT INTO model_asset (id, name, status, format, original_filename, size_bytes, storage_key,
                                                 lon, lat, height_m, heading_deg, scale)
                        VALUES (:id, :name, 'PROCESSING', :format, :filename, :size, :key, :lon, :lat, :h, :heading, :scale)""")
                .param("id", id)
                .param("name", name == null || name.isBlank() ? filename : name)
                .param("format", format)
                .param("filename", filename)
                .param("size", (long) data.length)
                .param("key", key)
                .param("lon", placement == null ? null : placement.lon())
                .param("lat", placement == null ? null : placement.lat())
                .param("h", placement == null ? 0 : placement.heightM())
                .param("heading", placement == null ? 0 : placement.headingDeg())
                .param("scale", placement == null ? 1 : placement.scale())
                .update();
        executor.submit(() -> process(id, key, filename, data));
        return get(id);
    }

    private void process(UUID id, String key, String filename, byte[] data) {
        try {
            var inspection = inspector.inspect(data, filename);
            storage.put(key, data, inspection.format().equals("glb") ? "model/gltf-binary" : "model/gltf+json");
            Map<String, Object> stats = new LinkedHashMap<>(inspection.stats());
            stats.put("warnings", inspection.warnings());
            stats.put("storage", storage.kind());
            jdbc.sql("UPDATE model_asset SET status = 'READY', stats = CAST(:stats AS jsonb), updated_at = now() WHERE id = :id")
                    .param("stats", mapper.writeValueAsString(stats))
                    .param("id", id)
                    .update();
        } catch (RuntimeException e) {
            log.warn("모델 {} 처리 실패: {}", id, e.getMessage());
            jdbc.sql("UPDATE model_asset SET status = 'FAILED', error = :error, updated_at = now() WHERE id = :id")
                    .param("error", e.getMessage())
                    .param("id", id)
                    .update();
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
        String key = jdbc.sql("SELECT storage_key FROM model_asset WHERE id = :id").param("id", id)
                .query(String.class).single();
        return storage.get(key);
    }

    public void delete(UUID id) {
        String key = jdbc.sql("SELECT storage_key FROM model_asset WHERE id = :id").param("id", id)
                .query(String.class).optional()
                .orElseThrow(() -> new NoSuchElementException("모델을 찾을 수 없습니다: " + id));
        jdbc.sql("DELETE FROM model_asset WHERE id = :id").param("id", id).update();
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("저장소 파일 삭제 실패(메타데이터는 삭제됨): {}", key, e);
        }
    }

    private static final String SELECT = """
            SELECT id, name, status, error, format, original_filename, size_bytes, stats::text AS stats, lon, lat,
                   height_m, heading_deg, scale, created_at, updated_at FROM model_asset""";

    private ModelAsset row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ModelAsset(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getString("error"), rs.getString("format"), rs.getString("original_filename"),
                (Long) rs.getObject("size_bytes"), rs.getString("stats"),
                (Double) rs.getObject("lon"), (Double) rs.getObject("lat"), rs.getDouble("height_m"),
                rs.getDouble("heading_deg"), rs.getDouble("scale"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
