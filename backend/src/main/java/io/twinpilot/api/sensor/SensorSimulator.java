package io.twinpilot.api.sensor;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.config.TwinPilotProperties;

/**
 * IoT 센서 시뮬레이터.
 * - 소화전 수압 센서: 기준값 주변 변동, 일부 센서는 점진적으로 수압이 떨어져 경보를 낸다.
 * - 버스 이동형 공기질 센서: 노선을 따라 이동하며 PM2.5/PM10/VOCs를 측정한다(부산 사례 모델).
 * 최신값은 메모리에 두고 SSE로 방송하며, 주기적으로 sensor_reading 테이블에 배치 저장한다.
 */
@Component
public class SensorSimulator {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulator.class);

    private record SensorDef(int id, String code, String kind, Integer facilityId, Integer routeId, double base,
                             double offset, double lon, double lat) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;
    private final TwinPilotProperties.Sensors config;
    private final Random random = new Random(7);
    private final Map<Integer, SensorReading> latest = new ConcurrentHashMap<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<Integer, List<double[]>> routes = new ConcurrentHashMap<>();
    private volatile List<SensorDef> defs = List.of();
    private long tick;

    public SensorSimulator(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper mapper, TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
        this.config = props.sensors();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        defs = jdbc.sql("""
                        SELECT s.id, s.code, s.kind, s.facility_id, s.route_id,
                               coalesce((s.properties ->> 'base')::float8, 0) AS base,
                               coalesce((s.properties ->> 'offset')::float8, 0) AS "offset",
                               ST_X(f.geom) AS lon, ST_Y(f.geom) AS lat
                          FROM sensor s LEFT JOIN facility f ON f.id = s.facility_id
                         ORDER BY s.id""")
                .query((rs, i) -> new SensorDef(rs.getInt("id"), rs.getString("code"), rs.getString("kind"),
                        (Integer) rs.getObject("facility_id"), (Integer) rs.getObject("route_id"),
                        rs.getDouble("base"), rs.getDouble("offset"), rs.getDouble("lon"), rs.getDouble("lat")))
                .list();
        jdbc.sql("""
                        SELECT r.id, (dp).path[1] AS idx, ST_X((dp).geom) AS lon, ST_Y((dp).geom) AS lat
                          FROM (SELECT id, ST_DumpPoints(geom) AS dp FROM bus_route) r
                         ORDER BY r.id, idx""")
                .query((rs, i) -> new Object[]{rs.getInt("id"), rs.getDouble("lon"), rs.getDouble("lat")})
                .list()
                .forEach(row -> routes.computeIfAbsent((Integer) row[0], k -> new ArrayList<>())
                        .add(new double[]{(Double) row[1], (Double) row[2]}));
        log.info("센서 {}개, 버스 노선 {}개 로드", defs.size(), routes.size());
        if (config.simulate()) {
            step();
        }
    }

    @Scheduled(fixedDelayString = "${twinpilot.sensors.interval-ms:2000}", initialDelay = 2000)
    public void scheduledStep() {
        if (config.simulate() && !defs.isEmpty()) {
            step();
            broadcast();
        }
    }

    synchronized void step() {
        tick++;
        Instant now = Instant.now();
        for (SensorDef d : defs) {
            latest.put(d.id(), switch (d.kind()) {
                case "HYDRANT_PRESSURE" -> pressure(d, now);
                default -> airQuality(d, now);
            });
        }
    }

    private SensorReading pressure(SensorDef d, Instant now) {
        double value = d.base() + random.nextGaussian() * 0.01;
        // 일부 센서는 누수·밸브 이상을 가정해 수압이 서서히 떨어진다(주기적으로 복구)
        if (d.id() % 37 == 5) {
            value = d.base() - (tick % 60) * 0.006;
        }
        value = Math.max(0.02, Math.round(value * 1000) / 1000.0);
        String status = value < config.lowPressureMpa() ? "ALERT" : "NORMAL";
        return new SensorReading(d.id(), d.code(), d.kind(), d.facilityId(), null, d.lon(), d.lat(),
                Map.of("pressureMpa", value), status, now);
    }

    private SensorReading airQuality(SensorDef d, Instant now) {
        List<double[]> path = routes.getOrDefault(d.routeId(), List.of());
        double t = (d.offset() + tick * 0.004) % 1.0;
        double[] pos = interpolate(path, t);
        double hotspot = (t > 0.55 && t < 0.7) ? 25 : 0;  // 공업지역 통과 구간 가정
        double pm25 = Math.max(3, d.base() + 10 * Math.sin(2 * Math.PI * t) + hotspot + random.nextGaussian() * 2);
        double pm10 = pm25 * 1.6 + random.nextGaussian() * 3;
        double voc = Math.max(5, 60 + 30 * Math.sin(2 * Math.PI * (t + 0.2)) + hotspot * 2 + random.nextGaussian() * 5);
        String status = pm25 <= 15 ? "GOOD" : pm25 <= 35 ? "NORMAL" : pm25 <= 75 ? "BAD" : "VERY_BAD";
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("pm25", round1(pm25));
        values.put("pm10", round1(pm10));
        values.put("vocPpb", round1(voc));
        return new SensorReading(d.id(), d.code(), d.kind(), null, d.routeId(), pos[0], pos[1], values, status, now);
    }

    /** 노선 길이 비율 t(0~1)에 해당하는 위치. 짧은 구간이라 경위도 선형 보간으로 충분하다. */
    static double[] interpolate(List<double[]> path, double t) {
        if (path.isEmpty()) {
            return new double[]{0, 0};
        }
        if (path.size() == 1) {
            return path.getFirst();
        }
        double total = 0;
        double[] seg = new double[path.size() - 1];
        for (int i = 0; i < seg.length; i++) {
            seg[i] = Math.hypot(path.get(i + 1)[0] - path.get(i)[0], path.get(i + 1)[1] - path.get(i)[1]);
            total += seg[i];
        }
        double target = t * total;
        for (int i = 0; i < seg.length; i++) {
            if (target <= seg[i] || i == seg.length - 1) {
                double f = seg[i] == 0 ? 0 : Math.min(1, target / seg[i]);
                double[] a = path.get(i);
                double[] b = path.get(i + 1);
                return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
            }
            target -= seg[i];
        }
        return path.getLast();
    }

    public List<SensorReading> snapshot() {
        return latest.values().stream().sorted(Comparator.comparingInt(SensorReading::sensorId)).toList();
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("readings").data(snapshot()));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    private void broadcast() {
        if (emitters.isEmpty()) {
            return;
        }
        List<SensorReading> data = snapshot();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("readings").data(data));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    /** 최신값을 배치로 저장하고 6시간 지난 기록은 지운다. */
    @Scheduled(fixedDelayString = "${twinpilot.sensors.persist-interval-ms:10000}", initialDelay = 10000)
    public void persist() {
        List<SensorReading> data = snapshot();
        if (!config.simulate() || data.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO sensor_reading (sensor_id, ts, value, geom)
                        VALUES (?, ?, ?::jsonb, ST_SetSRID(ST_MakePoint(?, ?), 4326))""",
                data, 200, (ps, r) -> {
                    ps.setInt(1, r.sensorId());
                    ps.setTimestamp(2, Timestamp.from(r.ts()));
                    ps.setString(3, mapper.writeValueAsString(r.values()));
                    ps.setDouble(4, r.lon());
                    ps.setDouble(5, r.lat());
                });
        jdbc.sql("DELETE FROM sensor_reading WHERE ts < now() - interval '6 hours'").update();
    }

    /**
     * 컨텍스트 종료 신호를 받으면 SSE 연결을 먼저 닫는다.
     * 그러지 않으면 graceful shutdown이 열린 스트림을 기다리느라 파드 종료가 지연된다.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeStreams() {
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    public int subscriberCount() {
        return emitters.size();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
