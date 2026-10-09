package io.twinpilot.api.sensor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.twinpilot.api.common.LeaderElection;
import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.sensor.SensorIngestService.Reading;

/**
 * 데모용 IoT 센서 시뮬레이터. 레플리카 중 리더 하나만 측정값을 만들고, 실제 게이트웨이와 같은 수집 경로로 넣는다.
 * - 소화전 수압 센서: 기준값 주변 변동, 일부 센서는 점진적으로 수압이 떨어져 경보를 낸다.
 * - 버스 이동형 공기질 센서: 노선을 따라 이동하며 PM2.5/PM10/VOCs를 측정한다(부산 사례 모델).
 * 실제 센서를 연결하면 SENSOR_SIMULATE=false로 끈다.
 */
@Component
public class SensorSimulator {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulator.class);
    static final String LEASE = "sensor-simulator";

    private record SensorDef(int id, String kind, Integer routeId, double base, double offset) {
    }

    private final JdbcClient jdbc;
    private final SensorIngestService ingest;
    private final LeaderElection leader;
    private final TwinPilotProperties.Sensors config;
    private final Random random = new Random(7);
    private List<SensorDef> defs = List.of();
    private Map<Integer, List<double[]>> routes = Map.of();
    private long tick;

    public SensorSimulator(JdbcClient jdbc, SensorIngestService ingest, LeaderElection leader,
                           TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.ingest = ingest;
        this.leader = leader;
        this.config = props.sensors();
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 1_000)
    public void elect() {
        if (config.simulate()) {
            leader.acquire(LEASE);
        }
    }

    @Scheduled(fixedDelayString = "${twinpilot.sensors.interval-ms:2000}", initialDelay = 1_500)
    public void scheduledStep() {
        if (!config.simulate() || !leader.isLeader(LEASE)) {
            return;
        }
        try {
            if (defs.isEmpty()) {
                load();
            }
            if (!defs.isEmpty()) {
                ingest.ingest(step());
            }
        } catch (RuntimeException e) {
            log.warn("센서 시뮬레이션 실패: {}", e.toString());
        }
    }

    synchronized void load() {
        defs = jdbc.sql("""
                        SELECT s.id, s.kind, s.route_id,
                               coalesce((s.properties ->> 'base')::float8, 0) AS base,
                               coalesce((s.properties ->> 'offset')::float8, 0) AS "offset"
                          FROM sensor s ORDER BY s.id""")
                .query((rs, i) -> new SensorDef(rs.getInt("id"), rs.getString("kind"),
                        (Integer) rs.getObject("route_id"), rs.getDouble("base"), rs.getDouble("offset")))
                .list();
        Map<Integer, List<double[]>> loaded = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT r.id, (dp).path[1] AS idx, ST_X((dp).geom) AS lon, ST_Y((dp).geom) AS lat
                          FROM (SELECT id, ST_DumpPoints(geom) AS dp FROM bus_route) r
                         ORDER BY r.id, idx""")
                .query((rs, i) -> new Object[]{rs.getInt("id"), rs.getDouble("lon"), rs.getDouble("lat")})
                .list()
                .forEach(row -> loaded.computeIfAbsent((Integer) row[0], k -> new ArrayList<>())
                        .add(new double[]{(Double) row[1], (Double) row[2]}));
        routes = loaded;
        log.info("시뮬레이터: 센서 {}개, 버스 노선 {}개", defs.size(), routes.size());
    }

    synchronized List<Reading> step() {
        tick++;
        Instant now = Instant.now();
        List<Reading> out = new ArrayList<>(defs.size());
        for (SensorDef d : defs) {
            out.add(switch (d.kind()) {
                case "HYDRANT_PRESSURE" -> pressure(d, now);
                default -> airQuality(d, now);
            });
        }
        return out;
    }

    private Reading pressure(SensorDef d, Instant now) {
        double value = d.base() + random.nextGaussian() * 0.01;
        // 일부 센서는 누수·밸브 이상을 가정해 수압이 서서히 떨어진다(주기적으로 복구)
        if (d.id() % 37 == 5) {
            value = d.base() - (tick % 60) * 0.006;
        }
        value = Math.max(0.02, Math.round(value * 1000) / 1000.0);
        return new Reading(d.id(), null, now, Map.of("pressureMpa", value), null, null);
    }

    private Reading airQuality(SensorDef d, Instant now) {
        List<double[]> path = routes.getOrDefault(d.routeId(), List.of());
        double t = (d.offset() + tick * 0.004) % 1.0;
        double[] pos = interpolate(path, t);
        double hotspot = (t > 0.55 && t < 0.7) ? 25 : 0;  // 공업지역 통과 구간 가정
        double pm25 = Math.max(3, d.base() + 10 * Math.sin(2 * Math.PI * t) + hotspot + random.nextGaussian() * 2);
        double pm10 = Math.max(0, pm25 * 1.6 + random.nextGaussian() * 3);
        double voc = Math.max(5, 60 + 30 * Math.sin(2 * Math.PI * (t + 0.2)) + hotspot * 2 + random.nextGaussian() * 5);
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("pm25", round1(pm25));
        values.put("pm10", round1(pm10));
        values.put("vocPpb", round1(voc));
        return new Reading(d.id(), null, now, values, pos[0], pos[1]);
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

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
