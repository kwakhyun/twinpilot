package io.twinpilot.api.analysis;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.twinpilot.api.analysis.AnalysisDtos.CoverageRequest;
import io.twinpilot.api.analysis.AnalysisDtos.CoverageResult;
import io.twinpilot.api.analysis.AnalysisDtos.JobRequest;
import io.twinpilot.api.job.JobQueue;
import io.twinpilot.api.job.JobView;
import io.twinpilot.api.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final CoverageService coverage;
    private final JobQueue jobs;
    private final CurrentUser currentUser;

    public AnalysisController(CoverageService coverage, JobQueue jobs, CurrentUser currentUser) {
        this.coverage = coverage;
        this.jobs = jobs;
        this.currentUser = currentUser;
    }

    /** 커버리지·사각지대 분석(동기). 건물 수천 동 규모에서 1초 이내를 목표로 한다. */
    @PostMapping("/coverage")
    public CoverageResult coverage(@Valid @RequestBody CoverageRequest req) {
        return coverage.analyze(req);
    }

    /**
     * 무거운 분석 Job을 대기열에 넣는다. 202 Accepted + Location.
     * Idempotency-Key 헤더를 주면 같은 키의 재요청은 기존 Job을 돌려준다.
     */
    @PostMapping("/jobs")
    public ResponseEntity<JobView> createJob(@Valid @RequestBody JobRequest req,
                                             @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("facilityType", req.facilityType());
        params.put("count", req.count());
        params.put("radiusM", req.radiusM());
        params.put("regionId", req.regionId());
        params.put("cellSizeM", req.cellSizeM());
        if (key != null && (key.isBlank() || key.length() > 128)) {
            throw new IllegalArgumentException("Idempotency-Key는 1~128자여야 합니다.");
        }
        JobView job = jobs.submit(req.type(), params, currentUser.get(), key);
        return ResponseEntity.accepted().location(URI.create("/api/v1/analysis/jobs/" + job.id())).body(job);
    }

    /** 내 Job 목록. all=true는 ADMIN만. 결과 본문은 빠진다. */
    @GetMapping("/jobs")
    public List<JobView> listJobs(@RequestParam(defaultValue = "false") boolean all,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(defaultValue = "20") int limit) {
        return jobs.list(currentUser.get(), all, type, limit);
    }

    @GetMapping("/jobs/{id}")
    public JobView job(@PathVariable UUID id) {
        return jobs.get(id);
    }

    /** 대기 중이면 즉시 취소, 실행 중이면 다음 진행 단계에서 멈춘다. */
    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<JobView> cancel(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(jobs.cancel(id, currentUser.get()));
    }

    /** 진행률 SSE: progress, done, failed, cancelled */
    @GetMapping(path = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter jobEvents(@PathVariable UUID id) {
        return jobs.subscribe(id);
    }
}
