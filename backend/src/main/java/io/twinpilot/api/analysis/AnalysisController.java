package io.twinpilot.api.analysis;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.twinpilot.api.analysis.AnalysisDtos.CoverageRequest;
import io.twinpilot.api.analysis.AnalysisDtos.CoverageResult;
import io.twinpilot.api.analysis.AnalysisDtos.JobRequest;
import io.twinpilot.api.analysis.AnalysisJobService.JobView;
import io.twinpilot.api.seed.DemoDataSeeder;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final CoverageService coverage;
    private final RecommendationService recommendation;
    private final AnalysisJobService jobs;
    private final DemoDataSeeder seeder;

    public AnalysisController(CoverageService coverage, RecommendationService recommendation, AnalysisJobService jobs,
                              DemoDataSeeder seeder) {
        this.coverage = coverage;
        this.recommendation = recommendation;
        this.jobs = jobs;
        this.seeder = seeder;
    }

    /** 커버리지·사각지대 분석(동기). 건물 수천 동 규모에서 1초 이내를 목표로 한다. */
    @PostMapping("/coverage")
    public CoverageResult coverage(@Valid @RequestBody CoverageRequest req) {
        return coverage.analyze(req);
    }

    /** 무거운 분석 Job 생성. 202 Accepted + Location 헤더. */
    @PostMapping("/jobs")
    public ResponseEntity<JobView> createJob(@Valid @RequestBody JobRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("facilityType", req.facilityType());
        params.put("count", req.count());
        params.put("radiusM", req.radiusM());
        params.put("regionId", req.regionId());
        params.put("cellSizeM", req.cellSizeM());
        JobView job = switch (req.type()) {
            case "RECOMMEND_SITES" -> jobs.submit(req.type(), params, progress -> recommendation.recommend(req, progress));
            case "REFRESH_RISK" -> jobs.submit(req.type(), params, progress -> {
                progress.report(10, "화재위험도 재계산");
                return Map.of("updatedBuildings", seeder.refreshRisk());
            });
            default -> throw new IllegalArgumentException("지원하지 않는 Job 유형: " + req.type());
        };
        return ResponseEntity.accepted().location(URI.create("/api/v1/analysis/jobs/" + job.id())).body(job);
    }

    @GetMapping("/jobs/{id}")
    public JobView job(@PathVariable UUID id) {
        return jobs.get(id);
    }

    @GetMapping(path = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter jobEvents(@PathVariable UUID id) {
        return jobs.subscribe(id);
    }
}
