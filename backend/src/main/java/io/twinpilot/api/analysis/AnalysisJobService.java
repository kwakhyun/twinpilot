package io.twinpilot.api.analysis;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 무거운 분석을 비동기 Job으로 실행한다.
 * 요청은 즉시 202를 받고, 진행률은 SSE(progress/done/failed 이벤트)나 폴링으로 확인한다.
 * 상태는 메모리에 두고 생성·완료 시점에 analysis_job 테이블에 기록한다(재시작 후 조회 가능).
 */
@Service
public class AnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);
    private static final Duration RETENTION = Duration.ofHours(1);

    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

    public static final class Job {
        private final UUID id;
        private final String type;
        private final Map<String, Object> params;
        private final Instant createdAt = Instant.now();
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private volatile Status status = Status.QUEUED;
        private volatile int progress;
        private volatile String message = "대기 중";
        private volatile Object result;
        private volatile String error;
        private volatile Instant finishedAt;

        Job(UUID id, String type, Map<String, Object> params) {
            this.id = id;
            this.type = type;
            this.params = params;
        }

        public JobView view() {
            return new JobView(id, type, status.name(), progress, message, params, result, error, createdAt, finishedAt);
        }

        boolean finished() {
            return status == Status.SUCCEEDED || status == Status.FAILED;
        }
    }

    public record JobView(UUID id, String type, String status, int progress, String message,
                          Map<String, Object> params, Object result, String error, Instant createdAt,
                          Instant finishedAt) {
    }

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final ExecutorService executor;

    public AnalysisJobService(JdbcClient jdbc, ObjectMapper mapper, ExecutorService virtualThreadExecutor) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.executor = virtualThreadExecutor;
    }

    public JobView submit(String type, Map<String, Object> params,
                          Function<RecommendationService.Progress, Object> work) {
        evictFinished();
        Job job = new Job(UUID.randomUUID(), type, params);
        jobs.put(job.id, job);
        jdbc.sql("INSERT INTO analysis_job (id, type, status, params) VALUES (:id, :type, :status, CAST(:params AS jsonb))")
                .param("id", job.id)
                .param("type", type)
                .param("status", job.status.name())
                .param("params", mapper.writeValueAsString(params))
                .update();
        executor.submit(() -> run(job, work));
        return job.view();
    }

    /** 끝난 지 오래된 Job은 메모리에서 지운다(이력은 analysis_job 테이블에서 조회된다). */
    private void evictFinished() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobs.values().removeIf(j -> j.finished() && j.finishedAt != null && j.finishedAt.isBefore(cutoff));
    }

    private void run(Job job, Function<RecommendationService.Progress, Object> work) {
        job.status = Status.RUNNING;
        try {
            job.result = work.apply((pct, msg) -> {
                job.progress = pct;
                job.message = msg;
                emit(job, "progress", Map.of("progress", pct, "message", msg));
            });
            job.progress = 100;
            job.message = "완료";
            job.status = Status.SUCCEEDED;
        } catch (RuntimeException e) {
            log.warn("Job {} 실패", job.id, e);
            job.error = e.getMessage();
            job.message = "실패";
            job.status = Status.FAILED;
        }
        job.finishedAt = Instant.now();
        jdbc.sql("""
                        UPDATE analysis_job SET status = :status, progress = :progress, result = CAST(:result AS jsonb),
                               error = :error, finished_at = :finishedAt WHERE id = :id""")
                .param("status", job.status.name())
                .param("progress", job.progress)
                .param("result", job.result == null ? null : mapper.writeValueAsString(job.result))
                .param("error", job.error)
                .param("finishedAt", Timestamp.from(job.finishedAt))
                .param("id", job.id)
                .update();
        emit(job, job.status == Status.SUCCEEDED ? "done" : "failed", job.view());
        job.emitters.forEach(SseEmitter::complete);
        job.emitters.clear();
    }

    private void emit(Job job, String event, Object data) {
        for (SseEmitter emitter : job.emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                job.emitters.remove(emitter);
            }
        }
    }

    public JobView get(UUID id) {
        Job job = jobs.get(id);
        if (job != null) {
            return job.view();
        }
        return fromDb(id).orElseThrow(() -> new NoSuchElementException("Job을 찾을 수 없습니다: " + id));
    }

    @SuppressWarnings("unchecked")
    private Optional<JobView> fromDb(UUID id) {
        return jdbc.sql("""
                        SELECT id, type, status, progress, params::text AS params, result::text AS result, error,
                               created_at, finished_at FROM analysis_job WHERE id = :id""")
                .param("id", id)
                .query((rs, i) -> new JobView(id, rs.getString("type"), rs.getString("status"), rs.getInt("progress"),
                        "기록 조회", rs.getString("params") == null ? Map.of()
                        : mapper.readValue(rs.getString("params"), Map.class),
                        rs.getString("result") == null ? null : mapper.readValue(rs.getString("result"), JsonNode.class),
                        rs.getString("error"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()))
                .optional();
    }

    @EventListener(ContextClosedEvent.class)
    public void closeStreams() {
        jobs.values().forEach(job -> {
            job.emitters.forEach(SseEmitter::complete);
            job.emitters.clear();
        });
    }

    /** 이미 끝난 Job이면 최종 이벤트를 바로 보내고 닫는다. */
    public SseEmitter subscribe(UUID id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new NoSuchElementException("Job을 찾을 수 없습니다: " + id);
        }
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(Map.of("progress", job.progress, "message", job.message)));
            if (job.finished()) {
                emitter.send(SseEmitter.event().name(job.status == Status.SUCCEEDED ? "done" : "failed").data(job.view()));
                emitter.complete();
                return emitter;
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        job.emitters.add(emitter);
        emitter.onCompletion(() -> job.emitters.remove(emitter));
        emitter.onTimeout(() -> job.emitters.remove(emitter));
        // 등록 직전에 끝났을 수 있으므로 한 번 더 확인한다.
        if (job.finished() && job.emitters.remove(emitter)) {
            try {
                emitter.send(SseEmitter.event().name(job.status == Status.SUCCEEDED ? "done" : "failed").data(job.view()));
            } catch (IOException ignored) {
                // 클라이언트가 이미 끊김
            }
            emitter.complete();
        }
        return emitter;
    }
}
