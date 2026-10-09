package io.twinpilot.api.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.twinpilot.api.analysis.AnalysisDtos.BlindSpot;
import io.twinpilot.api.analysis.AnalysisDtos.CoverageRequest;
import io.twinpilot.api.analysis.AnalysisDtos.CoverageResult;
import io.twinpilot.api.analysis.AnalysisDtos.CoverageSummary;
import io.twinpilot.api.analysis.AnalysisDtos.RegionCoverage;
import io.twinpilot.api.analysis.AnalysisDtos.UncoveredBuilding;
import io.twinpilot.api.common.RadiusPolicy;

/**
 * 시설 커버리지 분석.
 * - 건물 커버 여부: 건물 외곽선과 시설 사이 수평거리가 기준 반경 이내인지(ST_DWithin, EPSG:5186 미터)
 * - 사각지대: 행정동 영역에서 시설 반경 버퍼의 합집합을 뺀 면적(200㎡ 미만 조각 제외)
 */
@Service
public class CoverageService {

    private final JdbcClient jdbc;
    private final RadiusPolicy radius;
    private final ExecutorService executor;

    public CoverageService(JdbcClient jdbc, RadiusPolicy radius, ExecutorService virtualThreadExecutor) {
        this.jdbc = jdbc;
        this.radius = radius;
        this.executor = virtualThreadExecutor;
    }

    private String regionCte() {
        return """
                WITH reg AS (
                    SELECT r.id, r.name, r.zone_type, r.geom_m, %s AS radius
                      FROM region r
                     WHERE (CAST(:regionId AS integer) IS NULL OR r.id = CAST(:regionId AS integer))
                ), bld AS (
                    SELECT b.id, b.name, b.use_type, b.built_year, b.floors, b.risk_score, b.region_id, b.geom, b.geom_m,
                           reg.radius,
                           EXISTS (SELECT 1 FROM facility f
                                    WHERE f.type = :ftype AND f.status IN (:statuses)
                                      AND ST_DWithin(f.geom_m, b.geom_m, reg.radius)) AS covered
                      FROM building b JOIN reg ON reg.id = b.region_id
                )
                """.formatted(radius.sqlExpr("r.zone_type"));
    }

    public CoverageResult analyze(CoverageRequest req) {
        long started = System.currentTimeMillis();
        Map<String, Object> p = new HashMap<>();
        radius.bind(p, req.facilityType(), req.radiusM(), req.planned());
        p.put("regionId", req.regionId());
        p.put("highRisk", radius.standards().highRiskThreshold());
        p.put("top", req.top());

        // 세 쿼리는 서로 독립적이라 가상 스레드에서 동시에 실행한다.
        var regionsF = CompletableFuture.supplyAsync(() -> regionStats(p), executor);
        var uncoveredF = CompletableFuture.supplyAsync(() -> uncovered(p), executor);
        var blindF = req.blindSpots()
                ? CompletableFuture.supplyAsync(() -> blindSpots(p), executor)
                : CompletableFuture.completedFuture(List.<BlindSpot>of());

        List<RegionCoverage> regions = regionsF.join();
        UncoveredSet uncovered = uncoveredF.join();
        List<BlindSpot> blind = blindF.join();

        int total = regions.stream().mapToInt(RegionCoverage::totalBuildings).sum();
        int covered = regions.stream().mapToInt(RegionCoverage::coveredBuildings).sum();
        int highRisk = regions.stream().mapToInt(RegionCoverage::uncoveredHighRisk).sum();
        double blindArea = blind.stream().mapToDouble(BlindSpot::areaM2).sum();
        var summary = new CoverageSummary(total, covered, rate(covered, total), highRisk, Math.round(blindArea));
        return new CoverageResult(req.facilityType(), req.planned(), req.radiusM(), summary, regions,
                uncovered.ids(), uncovered.top(), blind, standardText(req), System.currentTimeMillis() - started);
    }

    private List<RegionCoverage> regionStats(Map<String, Object> p) {
        return jdbc.sql(regionCte() + """
                        SELECT reg.id, reg.name, reg.zone_type, reg.radius,
                               count(bld.id) AS total,
                               count(bld.id) FILTER (WHERE bld.covered) AS covered,
                               count(bld.id) FILTER (WHERE NOT bld.covered AND bld.risk_score >= :highRisk) AS high_risk,
                               coalesce(sum(bld.risk_score) FILTER (WHERE NOT bld.covered), 0) AS risk_sum
                          FROM reg LEFT JOIN bld ON bld.region_id = reg.id
                         GROUP BY reg.id, reg.name, reg.zone_type, reg.radius
                         ORDER BY reg.id""")
                .params(p)
                .query((rs, i) -> {
                    int total = rs.getInt("total");
                    int covered = rs.getInt("covered");
                    return new RegionCoverage(rs.getInt("id"), rs.getString("name"), rs.getString("zone_type"),
                            rs.getDouble("radius"), total, covered, rate(covered, total), rs.getInt("high_risk"),
                            Math.round(rs.getDouble("risk_sum") * 10) / 10.0);
                })
                .list();
    }

    private record UncoveredSet(List<Integer> ids, List<UncoveredBuilding> top) {
    }

    private UncoveredSet uncovered(Map<String, Object> p) {
        List<UncoveredBuilding> all = jdbc.sql(regionCte() + """
                        SELECT bld.id, bld.name, bld.use_type, bld.built_year, bld.floors, bld.risk_score, bld.region_id,
                               coalesce((SELECT ST_Distance(f.geom_m, bld.geom_m) FROM facility f
                                          WHERE f.type = :ftype AND f.status IN (:statuses)
                                          ORDER BY f.geom_m <-> bld.geom_m LIMIT 1), -1) AS nearest_m,
                               ST_X(ST_PointOnSurface(bld.geom)) AS lon, ST_Y(ST_PointOnSurface(bld.geom)) AS lat
                          FROM bld
                         WHERE NOT bld.covered
                         ORDER BY bld.risk_score DESC NULLS LAST, bld.id""")
                .params(p)
                .query((rs, i) -> new UncoveredBuilding(rs.getInt("id"), rs.getString("name"),
                        rs.getString("use_type"), (Integer) rs.getObject("built_year"), rs.getInt("floors"),
                        (Double) rs.getObject("risk_score"), (Integer) rs.getObject("region_id"),
                        Math.round(rs.getDouble("nearest_m") * 10) / 10.0, rs.getDouble("lon"), rs.getDouble("lat")))
                .list();
        int top = (int) p.get("top");
        return new UncoveredSet(all.stream().map(UncoveredBuilding::id).toList(), all.stream().limit(top).toList());
    }

    private List<BlindSpot> blindSpots(Map<String, Object> p) {
        return jdbc.sql(regionCte() + """
                        , parts AS (
                            SELECT reg.id AS region_id,
                                   (ST_Dump(CASE WHEN u.g IS NULL THEN reg.geom_m
                                                 ELSE ST_Difference(reg.geom_m, u.g) END)).geom AS g
                              FROM reg
                              LEFT JOIN LATERAL (
                                  SELECT ST_Union(ST_Buffer(f.geom_m, reg.radius, 12)) AS g
                                    FROM facility f
                                   WHERE f.type = :ftype AND f.status IN (:statuses)
                                     AND ST_DWithin(f.geom_m, reg.geom_m, reg.radius)) u ON true
                        )
                        SELECT region_id, sum(ST_Area(g)) AS area,
                               ST_AsGeoJSON(ST_Transform(ST_Multi(ST_Collect(g)), 4326), 7) AS geometry
                          FROM parts
                         WHERE ST_Area(g) >= 200
                         GROUP BY region_id
                         ORDER BY region_id""")
                .params(p)
                .query((rs, i) -> new BlindSpot(rs.getInt("region_id"), Math.round(rs.getDouble("area")),
                        rs.getString("geometry")))
                .list();
    }

    private String standardText(CoverageRequest req) {
        var s = radius.standards();
        if (req.radiusM() != null) {
            return "사용자 지정 반경 %.0fm".formatted(req.radiusM());
        }
        return switch (req.facilityType()) {
            case "HYDRANT" -> "소방기본법 시행규칙 [별표 3]: 주거·상업·공업지역 %.0fm, 그 외 %.0fm 이하"
                    .formatted(s.hydrantUrbanM(), s.hydrantOtherM());
            case "AED" -> "AED 반경 %.0fm(가정값: 보행 약 4분)".formatted(s.aedM());
            default -> "소방서 %.0f분 도달 반경 %.0fm(직선거리×%.1f, %.0fkm/h 근사)".formatted(
                    s.responseTargetMin(), s.stationRadiusM(), s.detourFactor(), s.travelSpeedKmh());
        };
    }

    static double rate(int covered, int total) {
        return total == 0 ? 0 : Math.round(covered * 1000.0 / total) / 10.0;
    }
}
