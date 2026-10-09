package io.twinpilot.api.sensor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.common.PgNotificationListener;
import io.twinpilot.api.config.TwinPilotProperties;

/**
 * 센서 측정값 수집. 실제 IoT 게이트웨이(INGEST 역할 서비스 계정)와 데모 시뮬레이터가 같은 경로를 쓴다.
 * - 센서 존재·시각 범위·값 범위를 검증하고, 상태(정상/경보, 미세먼지 등급)는 서버가 판정한다.
 * - 이력은 (sensor_id, ts) 기본키로 중복 재전송을 무시하고, 최신값은 더 늦은 시각만 반영한다.
 * - 커밋 후 NOTIFY로 모든 레플리카에 갱신을 알린다.
 */
@Service
public class SensorIngestService {

    static final String CHANNEL = "tp_sensor_readings";
    private static final Duration MAX_PAST = Duration.ofDays(1);
    private static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    /** 수집 요청의 한 건. sensorId나 sensorCode 중 하나로 센서를 지정한다. 위치는 이동형 센서만 필요하다. */
    public record Reading(Integer sensorId, String sensorCode, Instant ts, Map<String, Double> values, Double lon,
                          Double lat) {
    }

    public record Result(int accepted, int rejected, List<String> errors) {
    }

    record SensorMeta(int id, String code, String kind, Integer facilityId, Integer routeId, Double lon, Double lat) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final PgNotificationListener notifier;
    private final TwinPilotProperties.Sensors config;
    private final Counter accepted;
    private final Counter rejected;
    private volatile Map<Integer, SensorMeta> byId = Map.of();
    private volatile Map<String, SensorMeta> byCode = Map.of();
    private volatile long loadedAt;

    public SensorIngestService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate tx, ObjectMapper mapper,
                               PgNotificationListener notifier, TwinPilotProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.mapper = mapper;
        this.notifier = notifier;
        this.config = props.sensors();
        this.accepted = Counter.builder("twinpilot.sensors.readings").tag("result", "accepted").register(meters);
        this.rejected = Counter.builder("twinpilot.sensors.readings").tag("result", "rejected").register(meters);
    }

    /** 센서 등록 정보. 1분마다, 또는 모르는 센서가 들어오면 다시 읽는다. */
    Map<Integer, SensorMeta> registry(boolean force) {
        if (force || System.currentTimeMillis() - loadedAt > 60_000) {
            List<SensorMeta> list = jdbc.sql("""
                            SELECT s.id, s.code, s.kind, s.facility_id, s.route_id, ST_X(f.geom) AS lon, ST_Y(f.geom) AS lat
                              FROM sensor s LEFT JOIN facility f ON f.id = s.facility_id""")
                    .query((rs, i) -> new SensorMeta(rs.getInt("id"), rs.getString("code"), rs.getString("kind"),
                            (Integer) rs.getObject("facility_id"), (Integer) rs.getObject("route_id"),
                            (Double) rs.getObject("lon"), (Double) rs.getObject("lat")))
                    .list();
            Map<Integer, SensorMeta> ids = new ConcurrentHashMap<>();
            Map<String, SensorMeta> codes = new ConcurrentHashMap<>();
            list.forEach(m -> {
                ids.put(m.id(), m);
                codes.put(m.code(), m);
            });
            byId = ids;
            byCode = codes;
            loadedAt = System.currentTimeMillis();
        }
        return byId;
    }

    public Result ingest(List<Reading> readings) {
        if (readings.size() > config.maxBatch()) {
            throw new IllegalArgumentException("한 번에 최대 %d건까지 보낼 수 있습니다.".formatted(config.maxBatch()));
        }
        registry(false);
        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < readings.size(); i++) {
            Reading r = readings.get(i);
            try {
                rows.add(validate(r, now));
            } catch (IllegalArgumentException e) {
                if (errors.size() < 20) {
                    errors.add("[%d] %s".formatted(i, e.getMessage()));
                }
            }
        }
        if (!rows.isEmpty()) {
            tx.executeWithoutResult(status -> {
                jdbcTemplate.batchUpdate("""
                                INSERT INTO sensor_reading (sensor_id, ts, value, status, geom)
                                VALUES (?, ?, ?::jsonb, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                                ON CONFLICT DO NOTHING""",
                        rows, 500, (ps, row) -> bind(ps, row));
                jdbcTemplate.batchUpdate("""
                                INSERT INTO sensor_latest (sensor_id, ts, value, status, geom)
                                VALUES (?, ?, ?::jsonb, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                                ON CONFLICT (sensor_id) DO UPDATE
                                   SET ts = EXCLUDED.ts, value = EXCLUDED.value, status = EXCLUDED.status,
                                       geom = EXCLUDED.geom, received_at = now()
                                 WHERE sensor_latest.ts < EXCLUDED.ts""",
                        rows, 500, (ps, row) -> bind(ps, row));
            });
            notifier.notify(CHANNEL, Integer.toString(rows.size()));
        }
        accepted.increment(rows.size());
        rejected.increment(readings.size() - rows.size());
        return new Result(rows.size(), readings.size() - rows.size(), errors);
    }

    private static void bind(java.sql.PreparedStatement ps, Object[] row) throws java.sql.SQLException {
        ps.setInt(1, (Integer) row[0]);
        ps.setTimestamp(2, (Timestamp) row[1]);
        ps.setString(3, (String) row[2]);
        ps.setString(4, (String) row[3]);
        ps.setDouble(5, (Double) row[4]);
        ps.setDouble(6, (Double) row[5]);
    }

    private Object[] validate(Reading r, Instant now) {
        SensorMeta meta = resolve(r);
        Instant ts = r.ts() == null ? now : r.ts();
        if (ts.isBefore(now.minus(MAX_PAST)) || ts.isAfter(now.plus(MAX_FUTURE))) {
            throw new IllegalArgumentException("측정 시각이 허용 범위(최근 1일~5분 뒤)를 벗어났습니다: " + ts);
        }
        Map<String, Double> values = r.values() == null ? Map.of() : r.values();
        Map<String, Double> clean = new LinkedHashMap<>();
        String status;
        double lon;
        double lat;
        switch (meta.kind()) {
            case "HYDRANT_PRESSURE" -> {
                double p = require(values, "pressureMpa", 0, 3);
                clean.put("pressureMpa", p);
                status = p < config.lowPressureMpa() ? "ALERT" : "NORMAL";
                if (meta.lon() == null) {
                    throw new IllegalArgumentException("소화전 위치가 없는 센서입니다: " + meta.code());
                }
                lon = meta.lon();
                lat = meta.lat();
            }
            case "AIR_QUALITY" -> {
                double pm25 = require(values, "pm25", 0, 1000);
                clean.put("pm25", pm25);
                clean.put("pm10", require(values, "pm10", 0, 2000));
                if (values.containsKey("vocPpb")) {
                    clean.put("vocPpb", require(values, "vocPpb", 0, 100_000));
                }
                // 국내 PM2.5 예보 등급: 좋음 ≤15, 보통 ≤35, 나쁨 ≤75, 매우나쁨 >75 (㎍/㎥)
                status = pm25 <= 15 ? "GOOD" : pm25 <= 35 ? "NORMAL" : pm25 <= 75 ? "BAD" : "VERY_BAD";
                if (r.lon() == null || r.lat() == null || Math.abs(r.lon()) > 180 || Math.abs(r.lat()) > 90) {
                    throw new IllegalArgumentException("이동형 센서는 유효한 lon·lat이 필요합니다: " + meta.code());
                }
                lon = r.lon();
                lat = r.lat();
            }
            default -> throw new IllegalArgumentException("지원하지 않는 센서 종류: " + meta.kind());
        }
        return new Object[]{meta.id(), Timestamp.from(ts), mapper.writeValueAsString(clean), status, lon, lat};
    }

    private SensorMeta resolve(Reading r) {
        SensorMeta meta = lookup(r);
        if (meta == null) {
            registry(true);
            meta = lookup(r);
        }
        if (meta == null) {
            throw new IllegalArgumentException("등록되지 않은 센서: " + (r.sensorId() != null ? r.sensorId() : r.sensorCode()));
        }
        return meta;
    }

    private SensorMeta lookup(Reading r) {
        if (r.sensorId() != null) {
            return byId.get(r.sensorId());
        }
        return r.sensorCode() == null ? null : byCode.get(r.sensorCode());
    }

    private static double require(Map<String, Double> values, String key, double min, double max) {
        Double v = values.get(key);
        if (v == null || v.isNaN() || v < min || v > max) {
            throw new IllegalArgumentException("%s 값이 없거나 범위(%s~%s)를 벗어났습니다.".formatted(key, min, max));
        }
        return v;
    }
}
