package io.twinpilot.api.imports;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import io.twinpilot.api.analysis.RiskService;
import io.twinpilot.api.audit.AuditService;
import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.job.JobHandler;
import io.twinpilot.api.job.Progress;
import io.twinpilot.api.model.storage.ObjectStorage;
import io.twinpilot.api.security.Actor;
import io.twinpilot.api.security.Role;

/**
 * GeoJSON 적재 Job(IMPORT).
 * 1) 저장소의 파일을 스트림으로 읽으며(피처 단위) 속성을 매핑·검증하고 스테이징 테이블에 배치로 넣는다.
 *    좌표계 변환(ST_Transform)·유효성 보정(ST_MakeValid)·국내 범위 검사·파일 내 중복 제거는 PostGIS가 한다.
 * 2) dryRun이면 보고서만 만들고, 아니면 한 트랜잭션에서 대상 테이블로 병합(upsert)한다.
 *    REPLACE 모드는 같은 출처(source)에서 이번 파일에 없는 행을 지운다(시설은 INACTIVE로 전환).
 * 3) 데이터가 바뀌면 도로망 출동 시간과 화재위험도를 다시 계산한다.
 */
@Service
public class GeoJsonImportService implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GeoJsonImportService.class);
    public static final String JOB_TYPE = "IMPORT";
    static final Set<Integer> ALLOWED_SRIDS = Set.of(4326, 3857, 5174, 5179, 5181, 5185, 5186, 5187, 5188);
    private static final Pattern EPSG = Pattern.compile("EPSG::?(\\d{4,5})");
    private static final int MAX_ERRORS = 200;

    public record ImportResult(String layer, String source, String mode, boolean dryRun, int srid, int total,
                               int valid, int invalid, int outOfBounds, int fixedGeometries, int duplicates,
                               int inserted, int updated, int removedOrDeactivated, List<String> errors,
                               List<Map<String, Object>> sample, Object riskRefresh, long elapsedMs) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ObjectStorage storage;
    private final RiskService risk;
    private final AuditService audit;
    private final TwinPilotProperties.Imports config;

    public GeoJsonImportService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate tx, ObjectMapper mapper,
                                ObjectStorage storage, RiskService risk, AuditService audit, TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.mapper = mapper;
        this.storage = storage;
        this.risk = risk;
        this.audit = audit;
        this.config = props.imports();
    }

    @Override
    public String type() {
        return JOB_TYPE;
    }

    @Override
    public Role requiredRole() {
        return Role.ADMIN;
    }

    @Override
    public Object run(JsonNode params, Actor actor, Progress progress) {
        long started = System.currentTimeMillis();
        UUID jobId = UUID.randomUUID();   // 스테이징 구분용(재시도 시 이전 시도와 섞이지 않게 매번 새로)
        ImportLayer layer = ImportLayer.valueOf(params.path("layer").asString());
        String source = params.path("source").asString();
        String mode = params.path("mode").asString("UPSERT");
        boolean dryRun = params.path("dryRun").asBoolean(false);
        boolean refresh = params.path("refreshRisk").asBoolean(true);
        ImportMapping mapping = ImportMapping.from(params.path("mapping"));
        FeatureNormalizer normalizer = new FeatureNormalizer(layer, mapping, "REPLACE".equals(mode));
        Integer requestedSrid = params.path("srid").isNumber() ? params.path("srid").asInt() : null;

        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> sample = new ArrayList<>();
        int[] counts = new int[2];   // total, valid
        int srid;
        try {
            progress.report(5, "파일 읽기·검증");
            srid = stage(params.path("storageKey").asString(), jobId, normalizer, requestedSrid, errors, sample, counts,
                    progress);
            progress.report(55, "좌표계 변환·유효성 보정");
            int outOfBounds = jdbc.sql("""
                            DELETE FROM import_staging WHERE job_id = :id
                               AND NOT ST_Intersects(geom, ST_MakeEnvelope(124, 33, 132, 39.5, 4326))""")
                    .param("id", jobId).update();
            if (outOfBounds > 0) {
                addError(errors, "국내 범위(경도 124~132, 위도 33~39.5) 밖 피처 %d개를 제외했습니다. 좌표계(srid=%d)가 맞는지 확인하세요."
                        .formatted(outOfBounds, srid));
            }
            int dim = switch (layer) {
                case BUILDING, REGION -> 3;
                case ROAD -> 2;
                default -> 1;
            };
            int fixed = jdbc.sql("""
                            UPDATE import_staging SET geom = ST_CollectionExtract(ST_MakeValid(geom), :dim)
                             WHERE job_id = :id AND NOT ST_IsValid(geom)""")
                    .param("dim", dim).param("id", jobId).update();
            int empty = jdbc.sql("DELETE FROM import_staging WHERE job_id = :id AND ST_IsEmpty(geom)")
                    .param("id", jobId).update();
            int duplicates = jdbc.sql("""
                            DELETE FROM import_staging s USING import_staging t
                             WHERE s.job_id = :id AND t.job_id = :id AND s.external_id = t.external_id AND s.seq < t.seq""")
                    .param("id", jobId).update();
            if (duplicates > 0) {
                addError(errors, "파일 안에서 외부 식별자가 겹친 피처 %d개는 마지막 것만 반영합니다.".formatted(duplicates));
            }
            int valid = counts[1] - outOfBounds - empty;
            int invalid = counts[0] - counts[1] + outOfBounds + empty;

            int[] merged = {0, 0, 0};
            Object riskResult = null;
            if (!dryRun && valid > 0) {
                progress.report(65, "대상 테이블 병합(%s)".formatted(mode));
                merged = tx.execute(status -> {
                    jdbc.sql("SET LOCAL statement_timeout = '30min'").update();
                    return merge(layer, jobId, source, mode);
                });
                audit.record(actor, "DATA_IMPORT", layer.name(), source,
                        Map.of("mode", mode, "inserted", merged[0], "updated", merged[1], "removed", merged[2],
                                "invalid", invalid));
                if (refresh) {
                    riskResult = risk.refreshAll(scaled(progress, 75, 98));
                }
            }
            return new ImportResult(layer.name(), source, mode, dryRun, srid, counts[0], valid, invalid, outOfBounds,
                    fixed, duplicates, merged[0], merged[1], merged[2], errors, sample, riskResult,
                    System.currentTimeMillis() - started);
        } finally {
            jdbc.sql("DELETE FROM import_staging WHERE job_id = :id").param("id", jobId).update();
        }
    }

    /** 피처를 하나씩 읽어 스테이징에 넣는다. @return 적용한 SRID */
    private int stage(String key, UUID jobId, FeatureNormalizer normalizer, Integer requestedSrid, List<String> errors,
                      List<Map<String, Object>> sample, int[] counts, Progress progress) {
        int srid = requestedSrid == null ? 4326 : requestedSrid;
        List<Object[]> batch = new ArrayList<>(config.batchSize());
        // 스트림 중간에서 피처 하나씩 트리로 읽으므로 뒤따르는 토큰을 오류로 보지 않는다(Jackson 3 기본값은 오류).
        ObjectReader nodes = mapper.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (InputStream in = storage.get(key); JsonParser p = mapper.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("GeoJSON FeatureCollection 객체가 아닙니다.");
            }
            boolean sawFeatures = false;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = p.currentName();
                p.nextToken();
                if ("crs".equals(name)) {
                    JsonNode crs = nodes.readValue(p);
                    Matcher m = EPSG.matcher(crs.path("properties").path("name").asString(""));
                    if (requestedSrid == null && m.find()) {
                        srid = Integer.parseInt(m.group(1));
                    }
                } else if ("features".equals(name) && p.currentToken() == JsonToken.START_ARRAY) {
                    sawFeatures = true;
                    if (!ALLOWED_SRIDS.contains(srid)) {
                        throw new IllegalArgumentException("지원하지 않는 좌표계 EPSG:%d (허용: %s)".formatted(srid, ALLOWED_SRIDS));
                    }
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode feature = nodes.readValue(p);
                        int seq = counts[0]++;
                        if (counts[0] > config.maxFeatures()) {
                            throw new IllegalArgumentException("피처가 최대 %d개를 넘습니다.".formatted(config.maxFeatures()));
                        }
                        try {
                            var n = normalizer.normalize(feature);
                            batch.add(new Object[]{seq, n.externalId(), mapper.writeValueAsString(n.props()),
                                    n.geometryJson()});
                            if (sample.size() < 3) {
                                Map<String, Object> s = new LinkedHashMap<>(n.props());
                                s.put("externalId", n.externalId());
                                sample.add(s);
                            }
                        } catch (IllegalArgumentException e) {
                            addError(errors, "#%d: %s".formatted(seq, e.getMessage()));
                        }
                        if (batch.size() >= config.batchSize()) {
                            counts[1] += flush(jobId, srid, batch, errors);
                            progress.report(5 + Math.min(45, counts[0] / 2000), "피처 %d개 검증".formatted(counts[0]));
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
            if (!sawFeatures) {
                throw new IllegalArgumentException("features 배열이 없습니다.");
            }
            counts[1] += flush(jobId, srid, batch, errors);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("GeoJSON 구문 오류(피처 %d 부근): %s".formatted(counts[0], e.getOriginalMessage()));
        }
        return srid;
    }

    private static final String STAGE_SQL = """
            INSERT INTO import_staging (job_id, seq, external_id, props, geom)
            VALUES (?, ?, ?, ?::jsonb, ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON(?), ?), 4326))""";

    /** 배치로 넣고, 실패하면 한 건씩 다시 넣어 잘못된 지오메트리만 걸러낸다. @return 성공 건수 */
    private int flush(UUID jobId, int srid, List<Object[]> batch, List<String> errors) {
        if (batch.isEmpty()) {
            return 0;
        }
        int ok;
        try {
            tx.executeWithoutResult(s -> jdbcTemplate.batchUpdate(STAGE_SQL, batch, batch.size(),
                    (ps, row) -> bind(ps, jobId, srid, row)));
            ok = batch.size();
        } catch (DataAccessException e) {
            ok = 0;
            for (Object[] row : batch) {
                try {
                    jdbcTemplate.update(STAGE_SQL, ps -> bind(ps, jobId, srid, row));
                    ok++;
                } catch (DataAccessException one) {
                    String cause = one.getMostSpecificCause().getMessage();
                    addError(errors, "#%s: 지오메트리를 해석할 수 없습니다(%s)".formatted(row[0],
                            cause == null ? "" : cause.lines().findFirst().orElse("")));
                }
            }
        }
        batch.clear();
        return ok;
    }

    private static void bind(java.sql.PreparedStatement ps, UUID jobId, int srid, Object[] row)
            throws java.sql.SQLException {
        ps.setObject(1, jobId);
        ps.setInt(2, (Integer) row[0]);
        ps.setString(3, (String) row[1]);
        ps.setString(4, (String) row[2]);
        ps.setString(5, (String) row[3]);
        ps.setInt(6, srid);
    }

    /** @return {inserted, updated, removed(or deactivated)} */
    private int[] merge(ImportLayer layer, UUID jobId, String source, String mode) {
        int[] counts = switch (layer) {
            case BUILDING -> upsert("""
                    INSERT INTO building (source, external_id, name, use_type, structure, floors, basement_floors,
                                          height_m, built_year, region_id, geom, updated_at)
                    SELECT :source, s.external_id, s.props ->> 'name', s.props ->> 'useType', s.props ->> 'structure',
                           (s.props ->> 'floors')::int, (s.props ->> 'basementFloors')::int, (s.props ->> 'heightM')::float8,
                           (s.props ->> 'builtYear')::int,
                           (SELECT r.id FROM region r WHERE ST_Contains(r.geom, ST_PointOnSurface(s.geom)) LIMIT 1),
                           ST_Multi(s.geom), now()
                      FROM import_staging s WHERE s.job_id = :id
                    ON CONFLICT (source, external_id) WHERE external_id IS NOT NULL DO UPDATE
                       SET name = EXCLUDED.name, use_type = EXCLUDED.use_type, structure = EXCLUDED.structure,
                           floors = EXCLUDED.floors, basement_floors = EXCLUDED.basement_floors,
                           height_m = EXCLUDED.height_m, built_year = EXCLUDED.built_year,
                           region_id = EXCLUDED.region_id, geom = EXCLUDED.geom, updated_at = now()""", jobId, source);
            case HYDRANT, AED, FIRE_STATION -> upsert("""
                    INSERT INTO facility (source, external_id, type, subtype, name, status, installed_year, geom,
                                          updated_at)
                    SELECT :source, s.external_id, '%s', s.props ->> 'subtype', s.props ->> 'name', s.props ->> 'status',
                           (s.props ->> 'installedYear')::int, s.geom, now()
                      FROM import_staging s WHERE s.job_id = :id
                    ON CONFLICT (source, external_id) WHERE external_id IS NOT NULL DO UPDATE
                       SET subtype = EXCLUDED.subtype, name = EXCLUDED.name, status = EXCLUDED.status,
                           installed_year = EXCLUDED.installed_year, geom = EXCLUDED.geom, updated_at = now()"""
                    .formatted(layer.name()), jobId, source);
            case ROAD -> upsert("""
                    INSERT INTO road (source, external_id, name, width_m, geom, updated_at)
                    SELECT :source, CASE WHEN s.external_id IS NULL THEN NULL
                                         WHEN d.path[1] IS NULL THEN s.external_id
                                         ELSE s.external_id || '#' || d.path[1] END,
                           s.props ->> 'name', (s.props ->> 'widthM')::float8, d.geom, now()
                      FROM import_staging s CROSS JOIN LATERAL ST_Dump(s.geom) d
                     WHERE s.job_id = :id
                    ON CONFLICT (source, external_id) WHERE external_id IS NOT NULL DO UPDATE
                       SET name = EXCLUDED.name, width_m = EXCLUDED.width_m, geom = EXCLUDED.geom, updated_at = now()""",
                    jobId, source);
            case REGION -> upsert("""
                    INSERT INTO region (source, external_id, code, name, zone_type, geom, updated_at)
                    SELECT :source, s.external_id, s.props ->> 'code', s.props ->> 'name', s.props ->> 'zoneType',
                           ST_Multi(s.geom), now()
                      FROM import_staging s WHERE s.job_id = :id
                    ON CONFLICT (code) DO UPDATE
                       SET name = EXCLUDED.name, zone_type = EXCLUDED.zone_type, geom = EXCLUDED.geom,
                           source = EXCLUDED.source, external_id = EXCLUDED.external_id, updated_at = now()""",
                    jobId, source);
        };
        if ("REPLACE".equals(mode)) {
            String missing = "source = :source AND (external_id IS NULL OR external_id NOT IN "
                    + "(SELECT external_id FROM import_staging WHERE job_id = :id AND external_id IS NOT NULL))";
            counts[2] = switch (layer) {
                case BUILDING -> jdbc.sql("DELETE FROM building WHERE " + missing)
                        .param("source", source).param("id", jobId).update();
                case HYDRANT, AED, FIRE_STATION -> jdbc.sql("UPDATE facility SET status = 'INACTIVE', updated_at = now()"
                                + " WHERE type = :type AND status = 'ACTIVE' AND " + missing)
                        .param("type", layer.name()).param("source", source).param("id", jobId).update();
                case ROAD -> jdbc.sql("""
                                DELETE FROM road WHERE source = :source AND (external_id IS NULL
                                   OR split_part(external_id, '#', 1) NOT IN
                                      (SELECT external_id FROM import_staging WHERE job_id = :id AND external_id IS NOT NULL))""")
                        .param("source", source).param("id", jobId).update();
                case REGION -> throw new IllegalArgumentException("행정구역은 REPLACE 모드를 지원하지 않습니다(건물 소속이 끊김).");
            };
        }
        if (layer == ImportLayer.REGION) {
            jdbc.sql("""
                    UPDATE building b
                       SET region_id = (SELECT r.id FROM region r WHERE ST_Contains(r.geom, ST_PointOnSurface(b.geom)) LIMIT 1)""")
                    .update();
        }
        return counts;
    }

    private int[] upsert(String sql, UUID jobId, String source) {
        List<Boolean> inserted = jdbc.sql(sql + " RETURNING (xmax = 0) AS inserted")
                .param("source", source).param("id", jobId)
                .query(Boolean.class).list();
        int ins = (int) inserted.stream().filter(Boolean::booleanValue).count();
        return new int[]{ins, inserted.size() - ins, 0};
    }

    private static void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        }
    }

    /** 하위 단계 진행률(0~100)을 [from, to] 구간으로 옮긴다. */
    private static Progress scaled(Progress progress, int from, int to) {
        return (pct, msg) -> progress.report(from + (to - from) * pct / 100, msg);
    }
}
