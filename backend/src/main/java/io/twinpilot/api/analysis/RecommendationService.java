package io.twinpilot.api.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.twinpilot.api.analysis.AnalysisDtos.CoverageRequest;
import io.twinpilot.api.analysis.AnalysisDtos.JobRequest;
import io.twinpilot.api.analysis.AnalysisDtos.RecommendationResult;
import io.twinpilot.api.analysis.AnalysisDtos.Site;
import io.twinpilot.api.common.RadiusPolicy;

/**
 * 최대 커버리지 입지 문제(MCLP)를 탐욕 알고리즘으로 푼다.
 * 1) 도로 중심선을 따라 후보점 생성 → 2) 후보별로 반경 안 미커버 건물(위험도 가중) 집합 계산
 * → 3) 미커버 위험도 합이 가장 큰 후보를 k번 선택. 탐욕 해는 최적해 대비 (1-1/e) 근사가 보장된다.
 */
@Service
public class RecommendationService {

    public interface Progress {
        void report(int percent, String message);
    }

    private final JdbcClient jdbc;
    private final RadiusPolicy radius;
    private final CoverageService coverage;

    public RecommendationService(JdbcClient jdbc, RadiusPolicy radius, CoverageService coverage) {
        this.jdbc = jdbc;
        this.radius = radius;
        this.coverage = coverage;
    }

    private static final class Candidate {
        final int id;
        final double lon;
        final double lat;
        final int regionId;
        final double radius;
        final Map<Integer, Double> buildings = new HashMap<>();

        Candidate(int id, double lon, double lat, int regionId, double radius) {
            this.id = id;
            this.lon = lon;
            this.lat = lat;
            this.regionId = regionId;
            this.radius = radius;
        }
    }

    public RecommendationResult recommend(JobRequest req, Progress progress) {
        long started = System.currentTimeMillis();
        String type = req.facilityType() == null ? "HYDRANT" : req.facilityType();
        int k = req.count() == null ? 3 : req.count();
        int cell = req.cellSizeM() == null ? 25 : req.cellSizeM();

        progress.report(5, "현재 커버리지 계산");
        var before = coverage.analyze(new CoverageRequest(type, req.radiusM(), req.regionId(), true, false, 0));

        progress.report(20, "도로변 후보(%dm 간격) 생성 및 미커버 건물 매칭".formatted(cell));
        Map<String, Object> p = new HashMap<>();
        radius.bind(p, type, req.radiusM(), true);
        p.put("regionId", req.regionId());
        p.put("cell", cell);
        Map<Integer, Candidate> candidates = new LinkedHashMap<>();
        Map<Integer, String> regionNames = new HashMap<>();
        jdbc.sql("""
                        WITH reg AS (
                            SELECT r.id, r.name, r.zone_type, r.geom_m, %s AS radius
                              FROM region r
                             WHERE (CAST(:regionId AS integer) IS NULL OR r.id = CAST(:regionId AS integer))
                        ), cand AS MATERIALIZED (
                            -- 도로 중심선을 일정 간격 이하로 나눈 점을 후보로 쓴다(소화전은 도로변에 설치).
                            SELECT row_number() OVER () AS cid, reg.id AS region_id, d.geom AS p
                              FROM road rd
                              CROSS JOIN LATERAL ST_DumpPoints(ST_Segmentize(rd.geom_m, :cell)) d
                              JOIN reg ON ST_Intersects(reg.geom_m, d.geom)
                        ), uncov AS MATERIALIZED (
                            SELECT b.id, b.risk_score, b.geom_m, reg.radius
                              FROM building b JOIN reg ON reg.id = b.region_id
                             WHERE NOT EXISTS (SELECT 1 FROM facility f
                                                WHERE f.type = :ftype AND f.status IN (:statuses)
                                                  AND ST_DWithin(f.geom_m, b.geom_m, reg.radius))
                        )
                        SELECT c.cid, c.region_id, reg.name AS region_name, reg.radius AS site_radius,
                               ST_X(ST_Transform(c.p, 4326)) AS lon, ST_Y(ST_Transform(c.p, 4326)) AS lat,
                               u.id AS building_id, coalesce(u.risk_score, 0) AS risk
                          FROM cand c
                          JOIN reg ON reg.id = c.region_id
                          JOIN uncov u ON ST_DWithin(c.p, u.geom_m, u.radius)""".formatted(radius.sqlExpr("r.zone_type")))
                .params(p)
                .query(rs -> {
                    double lon = rs.getDouble("lon");
                    double lat = rs.getDouble("lat");
                    int regionId = rs.getInt("region_id");
                    double siteRadius = rs.getDouble("site_radius");
                    Candidate c = candidates.computeIfAbsent(rs.getInt("cid"),
                            id -> new Candidate(id, lon, lat, regionId, siteRadius));
                    c.buildings.put(rs.getInt("building_id"), rs.getDouble("risk"));
                    regionNames.putIfAbsent(regionId, rs.getString("region_name"));
                });

        progress.report(50, "후보 %d개에서 %d개 입지 선택".formatted(candidates.size(), k));
        Set<Integer> covered = new HashSet<>();
        List<Site> sites = new ArrayList<>();
        for (int rank = 1; rank <= k; rank++) {
            Candidate best = null;
            double bestGain = 0;
            for (Candidate c : candidates.values()) {
                double gain = 0;
                for (var e : c.buildings.entrySet()) {
                    if (!covered.contains(e.getKey())) {
                        gain += 1 + e.getValue();  // 건물 수 + 위험도 가중
                    }
                }
                if (gain > bestGain) {
                    bestGain = gain;
                    best = c;
                }
            }
            if (best == null) {
                break;
            }
            List<Integer> newly = best.buildings.keySet().stream().filter(id -> !covered.contains(id)).sorted().toList();
            double risk = newly.stream().mapToDouble(best.buildings::get).sum();
            covered.addAll(newly);
            sites.add(new Site(rank, round6(best.lon), round6(best.lat), best.regionId, regionNames.get(best.regionId),
                    best.radius, newly.size(), Math.round(risk * 10) / 10.0, newly));
            candidates.remove(best.id);
            progress.report(50 + 45 * rank / k, "%d번째 입지 선택: 신규 커버 %d동".formatted(rank, newly.size()));
        }

        int total = before.summary().totalBuildings();
        int coveredBefore = before.summary().coveredBuildings();
        double riskCovered = sites.stream().mapToDouble(Site::riskCovered).sum();
        return new RecommendationResult(type, k, sites, candidates.size() + sites.size(), total,
                before.summary().coverageRate(), CoverageService.rate(coveredBefore + covered.size(), total),
                covered.size(), Math.round(riskCovered * 10) / 10.0,
                "MCLP 탐욕 근사(가중치 = 1 + 위험도), 도로 중심선 %dm 간격 후보".formatted(cell),
                System.currentTimeMillis() - started);
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
