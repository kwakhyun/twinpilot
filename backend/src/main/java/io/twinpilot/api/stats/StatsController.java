package io.twinpilot.api.stats;

import java.time.Duration;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.twinpilot.api.analysis.AnalysisDtos.CoverageRequest;
import io.twinpilot.api.analysis.AnalysisDtos.CoverageSummary;
import io.twinpilot.api.analysis.CoverageService;
import io.twinpilot.api.common.DataChangeEvents;
import io.twinpilot.api.common.SingleFlightCache;
import io.twinpilot.api.config.TwinPilotProperties;

/**
 * 대시보드 집계. 독립 집계 쿼리를 병렬로 실행한다.
 * 요청마다 쿼리 8개(커버리지 분석 2개 포함)가 커넥션을 하나씩 쓰므로, 결과를 짧게 캐시하고 동시 요청은 한 번만 계산한다.
 * 건물·시설·위험도가 바뀌면 DataChangeEvents로 모든 레플리카의 캐시를 비운다.
 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

    private final JdbcClient jdbc;
    private final CoverageService coverage;
    private final ExecutorService executor;
    private final TwinPilotProperties.Standards standards;
    private final SingleFlightCache<Integer, Summary> cache;

    public StatsController(JdbcClient jdbc, CoverageService coverage, ExecutorService virtualThreadExecutor,
                           TwinPilotProperties props, DataChangeEvents changes,
                           @Value("${twinpilot.stats.cache-ttl:15s}") Duration cacheTtl) {
        this.jdbc = jdbc;
        this.coverage = coverage;
        this.executor = virtualThreadExecutor;
        this.standards = props.standards();
        this.cache = new SingleFlightCache<>(cacheTtl, 256);
        changes.subscribe(cache::invalidateAll);
    }

    public record Totals(long buildings, Double avgRisk, long highRisk, long oldBuildings, double oldRatio) {
    }

    public record RegionStat(int regionId, String name, String zoneType, long buildings, Double avgRisk, long highRisk,
                             long oldBuildings, double oldRatio) {
    }

    public record Bucket(String key, long count) {
    }

    public record Summary(Totals totals, List<RegionStat> regions, List<Bucket> builtDecades, List<Bucket> riskHistogram,
                          List<Bucket> useTypes, List<Bucket> facilities, CoverageSummary hydrantCoverage,
                          CoverageSummary aedCoverage, int oldAgeYears, double highRiskThreshold) {
    }

    @GetMapping("/summary")
    public Summary summary(@RequestParam(defaultValue = "30") int oldAgeYears) {
        if (oldAgeYears < 1 || oldAgeYears > 200) {
            throw new IllegalArgumentException("oldAgeYears는 1~200 사이여야 합니다.");
        }
        return cache.get(oldAgeYears, () -> compute(oldAgeYears));
    }

    private Summary compute(int oldAgeYears) {
        Map<String, Object> p = Map.of("hr", standards.highRiskThreshold(),
                "oldYear", Year.now().getValue() - oldAgeYears);

        var totals = CompletableFuture.supplyAsync(() -> jdbc.sql("""
                        SELECT count(*) AS n, round(avg(risk_score)::numeric, 1) AS avg_risk,
                               count(*) FILTER (WHERE risk_score >= :hr) AS high,
                               count(*) FILTER (WHERE built_year <= :oldYear) AS old
                          FROM building""").params(p)
                .query((rs, i) -> new Totals(rs.getLong("n"), nullableDouble(rs.getObject("avg_risk")),
                        rs.getLong("high"), rs.getLong("old"), ratio(rs.getLong("old"), rs.getLong("n"))))
                .single(), executor);
        var regions = CompletableFuture.supplyAsync(() -> jdbc.sql("""
                        SELECT r.id, r.name, r.zone_type, count(b.id) AS n, round(avg(b.risk_score)::numeric, 1) AS avg_risk,
                               count(b.id) FILTER (WHERE b.risk_score >= :hr) AS high,
                               count(b.id) FILTER (WHERE b.built_year <= :oldYear) AS old
                          FROM region r LEFT JOIN building b ON b.region_id = r.id
                         GROUP BY r.id, r.name, r.zone_type ORDER BY r.id""").params(p)
                .query((rs, i) -> new RegionStat(rs.getInt("id"), rs.getString("name"), rs.getString("zone_type"),
                        rs.getLong("n"), nullableDouble(rs.getObject("avg_risk")), rs.getLong("high"),
                        rs.getLong("old"), ratio(rs.getLong("old"), rs.getLong("n"))))
                .list(), executor);
        var decades = buckets("""
                SELECT ((built_year / 10) * 10)::text || 's' AS k, count(*) AS n
                  FROM building WHERE built_year IS NOT NULL GROUP BY built_year / 10 ORDER BY built_year / 10""");
        var risk = buckets("""
                SELECT lpad((LEAST(floor(risk_score / 10), 9) * 10)::int::text, 2, '0') AS k, count(*) AS n
                  FROM building WHERE risk_score IS NOT NULL GROUP BY 1 ORDER BY 1""");
        var uses = buckets("SELECT use_type AS k, count(*) AS n FROM building GROUP BY use_type ORDER BY n DESC");
        var facilities = buckets("""
                SELECT type || ':' || status AS k, count(*) AS n FROM facility GROUP BY type, status ORDER BY type, status""");
        var hydrant = CompletableFuture.supplyAsync(() -> coverage.analyze(
                new CoverageRequest("HYDRANT", null, null, false, false, 0)).summary(), executor);
        var aed = CompletableFuture.supplyAsync(() -> coverage.analyze(
                new CoverageRequest("AED", null, null, false, false, 0)).summary(), executor);

        return new Summary(totals.join(), regions.join(), decades.join(), risk.join(), uses.join(), facilities.join(),
                hydrant.join(), aed.join(), oldAgeYears, standards.highRiskThreshold());
    }

    private CompletableFuture<List<Bucket>> buckets(String sql) {
        return CompletableFuture.supplyAsync(() -> jdbc.sql(sql)
                .query((rs, i) -> new Bucket(rs.getString("k"), rs.getLong("n"))).list(), executor);
    }

    private static Double nullableDouble(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static double ratio(long part, long total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }
}
