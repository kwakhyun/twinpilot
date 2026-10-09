package io.twinpilot.api.network;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.twinpilot.api.config.TwinPilotProperties;

/** 도로망 출동 시간 분석: 목표 시간(골든타임) 안 도달 비율, 행정동별 분포, 위험도 높은 미도달 건물. */
@Service
public class ResponseAnalysisService {

    public record Summary(int totalBuildings, int withinTarget, double withinRate, int beyondTarget, int unreachable,
                          int highRiskBeyond, int farFromRoad, Double p50Min, Double p90Min, Double maxMin) {
    }

    public record RegionResponse(int regionId, String regionName, int total, int withinTarget, double withinRate,
                                 Double p90Min) {
    }

    public record SlowBuilding(int id, String name, String useType, Double risk, double responseMin, String stationName,
                               Double roadAccessM, double lon, double lat) {
    }

    public record ResponseResult(double targetMin, String method, RoadNetworkService.NetworkState network,
                                 Summary summary, List<RegionResponse> regions, List<SlowBuilding> slowest,
                                 List<Integer> beyondTargetIds, long elapsedMs) {
    }

    private final JdbcClient jdbc;
    private final RoadNetworkService network;
    private final TwinPilotProperties props;

    public ResponseAnalysisService(JdbcClient jdbc, RoadNetworkService network, TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.network = network;
        this.props = props;
    }

    public ResponseResult analyze(Integer regionId, Double targetMin, int top) {
        long started = System.currentTimeMillis();
        double target = targetMin == null ? props.standards().responseTargetMin() : targetMin;
        double highRisk = props.standards().highRiskThreshold();
        String filter = "(CAST(:regionId AS integer) IS NULL OR b.region_id = CAST(:regionId AS integer))";

        Summary summary = jdbc.sql("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE b.response_s <= :targetS) AS within,
                               count(*) FILTER (WHERE b.response_s > :targetS) AS beyond,
                               count(*) FILTER (WHERE b.response_s IS NULL) AS unreachable,
                               count(*) FILTER (WHERE b.response_s > :targetS AND b.risk_score >= :highRisk) AS high_risk_beyond,
                               count(*) FILTER (WHERE b.road_access_m > 50) AS far_from_road,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY b.response_s) / 60 AS p50,
                               percentile_cont(0.9) WITHIN GROUP (ORDER BY b.response_s) / 60 AS p90,
                               max(b.response_s) / 60 AS max_min
                          FROM building b WHERE """ + filter)
                .param("targetS", target * 60)
                .param("highRisk", highRisk)
                .param("regionId", regionId)
                .query((rs, i) -> {
                    int total = rs.getInt("total");
                    int within = rs.getInt("within");
                    return new Summary(total, within, rate(within, total), rs.getInt("beyond"), rs.getInt("unreachable"),
                            rs.getInt("high_risk_beyond"), rs.getInt("far_from_road"), round1(rs, "p50"),
                            round1(rs, "p90"), round1(rs, "max_min"));
                })
                .single();

        List<RegionResponse> regions = jdbc.sql("""
                        SELECT r.id, r.name, count(b.id) AS total,
                               count(b.id) FILTER (WHERE b.response_s <= :targetS) AS within,
                               percentile_cont(0.9) WITHIN GROUP (ORDER BY b.response_s) / 60 AS p90
                          FROM region r JOIN building b ON b.region_id = r.id
                         WHERE """ + filter + """
                         GROUP BY r.id, r.name ORDER BY r.id""")
                .param("targetS", target * 60)
                .param("regionId", regionId)
                .query((rs, i) -> new RegionResponse(rs.getInt("id"), rs.getString("name"), rs.getInt("total"),
                        rs.getInt("within"), rate(rs.getInt("within"), rs.getInt("total")), round1(rs, "p90")))
                .list();

        List<SlowBuilding> slowest = jdbc.sql("""
                        SELECT b.id, b.name, b.use_type, b.risk_score, b.response_s / 60 AS minutes, f.name AS station,
                               b.road_access_m, ST_X(ST_PointOnSurface(b.geom)) AS lon, ST_Y(ST_PointOnSurface(b.geom)) AS lat
                          FROM building b LEFT JOIN facility f ON f.id = b.response_station_id
                         WHERE b.response_s > :targetS AND """ + filter + """
                         ORDER BY b.risk_score DESC NULLS LAST, b.response_s DESC LIMIT :top""")
                .param("targetS", target * 60)
                .param("regionId", regionId)
                .param("top", Math.clamp(top, 0, 200))
                .query((rs, i) -> new SlowBuilding(rs.getInt("id"), rs.getString("name"), rs.getString("use_type"),
                        (Double) rs.getObject("risk_score"), Math.round(rs.getDouble("minutes") * 10) / 10.0,
                        rs.getString("station"), round1(rs, "road_access_m"), rs.getDouble("lon"), rs.getDouble("lat")))
                .list();

        List<Integer> beyondIds = jdbc.sql("SELECT b.id FROM building b WHERE b.response_s > :targetS AND " + filter
                        + " ORDER BY b.id")
                .param("targetS", target * 60)
                .param("regionId", regionId)
                .query(Integer.class).list();

        var n = props.network();
        String method = ("도로망 최단경로(다중 출발점 Dijkstra). 폭 20m 이상 %.0fkm/h, 12m 이상 %.0fkm/h, 그 외 %.0fkm/h, "
                + "출동 준비 %.0f초 포함. 일방통행·신호·혼잡은 반영하지 않은 가정값")
                .formatted(n.wideRoadKmh(), n.mediumRoadKmh(), n.narrowRoadKmh(), n.turnoutSec());
        return new ResponseResult(target, method, network.state().orElse(null), summary, regions, slowest, beyondIds,
                System.currentTimeMillis() - started);
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private static Double round1(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : Math.round(((Number) v).doubleValue() * 10) / 10.0;
    }
}
