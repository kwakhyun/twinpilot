package io.twinpilot.api.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.job.Progress;

/**
 * 도로망 기반 출동 시간.
 * 1) road를 교차점에서 분할해 그래프를 만든다(도로가 바뀌었을 때만, PostGIS ST_Node).
 * 2) 소방서를 가장 가까운 도로 지점에 연결하고 다중 출발점 Dijkstra로 모든 노드의 도달 시간을 구한다.
 * 3) 건물은 가장 가까운 도로 지점까지의 시간 + 출동 준비 시간을 출동 시간으로 저장한다.
 * 그래프는 버전별로 메모리에 캐시한다(레플리카마다 한 번 적재).
 */
@Service
public class RoadNetworkService {

    private static final Logger log = LoggerFactory.getLogger(RoadNetworkService.class);
    private static final long BUILD_LOCK = 7_420_004L;

    /** 시설(소방서)이 도로망에 닿는 지점. */
    record Anchor(int edgeIndex, double fraction, double accessM, int originId) {
    }

    public record NetworkState(long version, int nodes, int edges, String builtAt, boolean stale) {
    }

    public record ComputeResult(long networkVersion, int stations, int buildings, int unreachable, long elapsedMs) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final TwinPilotProperties.Network config;
    private volatile RoadGraph cached;
    private final Map<String, String> isochroneCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 64;
        }
    });

    public RoadNetworkService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate tx,
                              TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.config = props.network();
    }

    public Optional<NetworkState> state() {
        return jdbc.sql("""
                        SELECT version, nodes, edges, built_at::text AS built_at,
                               roads_hash <> road_network_hash() AS stale
                          FROM road_network_state WHERE id = 1""")
                .query((rs, i) -> new NetworkState(rs.getLong("version"), rs.getInt("nodes"), rs.getInt("edges"),
                        rs.getString("built_at"), rs.getBoolean("stale")))
                .optional();
    }

    /** 도로가 바뀌었거나 그래프가 없으면 다시 만든다. 레플리카 간에는 트랜잭션 advisory lock으로 직렬화한다. */
    public boolean ensureBuilt() {
        Boolean rebuilt = tx.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '15min'").update();
            jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", BUILD_LOCK).query(Object.class).optional();
            boolean fresh = jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM road_network_state
                                            WHERE id = 1 AND roads_hash = road_network_hash())""")
                    .query(Boolean.class).single();
            if (fresh) {
                return false;
            }
            int edges = jdbc.sql("SELECT rebuild_road_network(:wide, :medium, :narrow)")
                    .param("wide", config.wideRoadKmh())
                    .param("medium", config.mediumRoadKmh())
                    .param("narrow", config.narrowRoadKmh())
                    .query(Integer.class).single();
            log.info("도로망 그래프 재생성: 간선 {}개", edges);
            return true;
        });
        if (Boolean.TRUE.equals(rebuilt)) {
            isochroneCache.clear();
        }
        return Boolean.TRUE.equals(rebuilt);
    }

    /** 현재 버전 그래프. 다른 레플리카가 다시 만들었으면 새로 읽는다. */
    public RoadGraph graph() {
        long version = jdbc.sql("SELECT coalesce((SELECT version FROM road_network_state WHERE id = 1), 0)")
                .query(Long.class).single();
        RoadGraph g = cached;
        if (g != null && g.version() == version) {
            return g;
        }
        synchronized (this) {
            if (cached != null && cached.version() == version) {
                return cached;
            }
            List<Integer> ids = jdbc.sql("SELECT id FROM road_node ORDER BY id").query(Integer.class).list();
            int[] nodeIds = ids.stream().mapToInt(Integer::intValue).toArray();
            Map<Integer, Integer> index = new java.util.HashMap<>();
            for (int i = 0; i < nodeIds.length; i++) {
                index.put(nodeIds[i], i);
            }
            List<RoadGraph.Edge> edges = jdbc.sql("SELECT id, source, target, cost_s FROM road_edge ORDER BY id")
                    .query((rs, i) -> new RoadGraph.Edge(rs.getInt("id"), index.get(rs.getInt("source")),
                            index.get(rs.getInt("target")), rs.getDouble("cost_s")))
                    .list();
            cached = new RoadGraph(version, nodeIds, edges);
            isochroneCache.clear();
            log.info("도로망 그래프 적재: v{} 노드 {} 간선 {}", version, nodeIds.length, edges.size());
            return cached;
        }
    }

    /** 활성 소방서에서 모든 노드까지의 최단 도달 시간(출동 준비 시간 포함). */
    RoadGraph.Result fromStations(RoadGraph g, Integer onlyStationId) {
        Map<Integer, Integer> edgeIndex = edgeIndex(g);
        List<Anchor> anchors = jdbc.sql("""
                        SELECT f.id, e.id AS edge_id, ST_LineLocatePoint(e.geom_m, f.geom_m) AS frac,
                               ST_Distance(e.geom_m, f.geom_m) AS access_m
                          FROM facility f
                          CROSS JOIN LATERAL (SELECT id, geom_m FROM road_edge ORDER BY geom_m <-> f.geom_m LIMIT 1) e
                         WHERE f.type = 'FIRE_STATION' AND f.status = 'ACTIVE'
                           AND (CAST(:sid AS integer) IS NULL OR f.id = CAST(:sid AS integer))""")
                .param("sid", onlyStationId)
                .query((rs, i) -> new Anchor(edgeIndex.get(rs.getInt("edge_id")), rs.getDouble("frac"),
                        rs.getDouble("access_m"), rs.getInt("id")))
                .list();
        double accessMps = config.accessKmh() / 3.6;
        List<RoadGraph.Seed> seeds = new ArrayList<>();
        for (Anchor a : anchors) {
            RoadGraph.Edge e = g.edges().get(a.edgeIndex());
            double base = config.turnoutSec() + a.accessM() / accessMps;
            seeds.add(new RoadGraph.Seed(e.source(), base + a.fraction() * e.costS(), a.originId()));
            seeds.add(new RoadGraph.Seed(e.target(), base + (1 - a.fraction()) * e.costS(), a.originId()));
        }
        return g.shortestFrom(seeds);
    }

    private static Map<Integer, Integer> edgeIndex(RoadGraph g) {
        Map<Integer, Integer> m = new java.util.HashMap<>(g.edges().size() * 2);
        for (int i = 0; i < g.edges().size(); i++) {
            m.put(g.edges().get(i).id(), i);
        }
        return m;
    }

    /** 건물별 출동 시간을 계산해 building.response_s에 저장한다. */
    public ComputeResult computeResponseTimes(Progress progress) {
        long started = System.currentTimeMillis();
        progress.report(15, "도로망 확인");
        ensureBuilt();
        RoadGraph g = graph();
        if (g.edges().isEmpty()) {
            return new ComputeResult(g.version(), 0, 0, 0, System.currentTimeMillis() - started);
        }
        progress.report(30, "소방서 기준 최단경로 탐색(노드 %d개)".formatted(g.nodeCount()));
        RoadGraph.Result r = fromStations(g, null);
        Map<Integer, Integer> edgeIndex = edgeIndex(g);
        progress.report(50, "건물별 최근접 도로 지점 매칭");
        List<Object[]> updates = new ArrayList<>();
        int[] unreachable = {0};
        jdbc.sql("""
                        SELECT b.id, e.id AS edge_id, ST_LineLocatePoint(e.geom_m, ST_PointOnSurface(b.geom_m)) AS frac,
                               ST_Distance(e.geom_m, b.geom_m) AS access_m
                          FROM building b
                          CROSS JOIN LATERAL (SELECT id, geom_m FROM road_edge ORDER BY geom_m <-> b.geom_m LIMIT 1) e""")
                .query(rs -> {
                    RoadGraph.Edge e = g.edges().get(edgeIndex.get(rs.getInt("edge_id")));
                    double f = rs.getDouble("frac");
                    double viaSource = r.costS()[e.source()] + f * e.costS();
                    double viaTarget = r.costS()[e.target()] + (1 - f) * e.costS();
                    double best = Math.min(viaSource, viaTarget);
                    int station = viaSource <= viaTarget ? r.origin()[e.source()] : r.origin()[e.target()];
                    if (Double.isInfinite(best)) {
                        unreachable[0]++;
                        updates.add(new Object[]{null, null, rs.getDouble("access_m"), rs.getInt("id")});
                    } else {
                        updates.add(new Object[]{Math.round(best * 10) / 10.0, station, rs.getDouble("access_m"),
                                rs.getInt("id")});
                    }
                });
        progress.report(75, "건물 %d동 출동 시간 저장".formatted(updates.size()));
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '15min'").update();
            jdbcTemplate.batchUpdate("""
                            UPDATE building SET response_s = ?, response_station_id = ?, road_access_m = ?
                             WHERE id = ?""", updates, 1000, (ps, u) -> {
                ps.setObject(1, u[0], java.sql.Types.DOUBLE);
                ps.setObject(2, u[1], java.sql.Types.INTEGER);
                ps.setObject(3, u[2], java.sql.Types.DOUBLE);
                ps.setInt(4, (Integer) u[3]);
            });
        });
        isochroneCache.clear();
        int stations = (int) java.util.Arrays.stream(r.origin()).filter(o -> o >= 0).distinct().count();
        return new ComputeResult(g.version(), stations, updates.size(), unreachable[0],
                System.currentTimeMillis() - started);
    }

    /**
     * 출동 도달권 폴리곤(GeoJSON FeatureCollection). 시간 기준마다 도달 가능한 도로 구간을 buffer해 합친다.
     * @param minutes   기준 시간(분), 오름차순
     * @param stationId 특정 소방서만(null이면 전체 중 가장 빠른 곳)
     * @param bufferM   도로에서 양옆으로 포함할 거리
     */
    public String isochrones(List<Double> minutes, Integer stationId, double bufferM) {
        RoadGraph g = graph();
        String key = g.version() + "|" + minutes + "|" + stationId + "|" + bufferM + "|" + stationsFingerprint();
        String hit = isochroneCache.get(key);
        if (hit != null) {
            return hit;
        }
        RoadGraph.Result r = fromStations(g, stationId);
        StringJoiner features = new StringJoiner(",");
        for (double m : minutes) {
            double limit = m * 60;
            StringJoiner ids = new StringJoiner(",", "{", "}");
            StringJoiner from = new StringJoiner(",", "{", "}");
            StringJoiner to = new StringJoiner(",", "{", "}");
            for (RoadGraph.Edge e : g.edges()) {
                double ds = r.costS()[e.source()];
                double dt = r.costS()[e.target()];
                double fs = ds < limit ? Math.min(1, (limit - ds) / e.costS()) : 0;
                double ft = dt < limit ? Math.min(1, (limit - dt) / e.costS()) : 0;
                if (fs + ft >= 1) {
                    add(ids, from, to, e.id(), 0, 1);
                } else {
                    if (fs > 0) {
                        add(ids, from, to, e.id(), 0, fs);
                    }
                    if (ft > 0) {
                        add(ids, from, to, e.id(), 1 - ft, 1);
                    }
                }
            }
            String geometry = jdbc.sql("""
                            SELECT ST_AsGeoJSON(ST_Transform(ST_SimplifyPreserveTopology(
                                       ST_Buffer(ST_Collect(ST_LineSubstring(e.geom_m, x.a, x.b)), :buf, 'quad_segs=4'),
                                       2), 4326), 6)
                              FROM unnest(CAST(:ids AS int[]), CAST(:froms AS float8[]), CAST(:tos AS float8[])) AS x(id, a, b)
                              JOIN road_edge e ON e.id = x.id
                             WHERE x.b > x.a""")
                    .param("buf", bufferM)
                    .param("ids", ids.toString())
                    .param("froms", from.toString())
                    .param("tos", to.toString())
                    .query(String.class).optional().orElse(null);
            features.add("""
                    {"type":"Feature","properties":{"minutes":%s},"geometry":%s}"""
                    .formatted(m, geometry == null ? "null" : geometry));
        }
        String fc = "{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}";
        isochroneCache.put(key, fc);
        return fc;
    }

    private static void add(StringJoiner ids, StringJoiner from, StringJoiner to, int id, double a, double b) {
        ids.add(Integer.toString(id));
        from.add(Double.toString(a));
        to.add(Double.toString(b));
    }

    /** 소방서 추가·이동·상태 변경을 캐시 키에 반영한다. */
    private String stationsFingerprint() {
        return jdbc.sql("""
                        SELECT coalesce(md5(string_agg(id || ':' || status || ':' || ST_AsText(geom), ',' ORDER BY id)), '')
                          FROM facility WHERE type = 'FIRE_STATION'""")
                .query(String.class).single();
    }
}
