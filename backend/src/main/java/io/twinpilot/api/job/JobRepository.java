package io.twinpilot.api.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.security.Actor;

/** analysis_job 테이블 접근. 작업 노드의 갱신은 모두 locked_by 조건을 걸어 임대를 잃은 노드가 결과를 덮어쓰지 못하게 한다. */
@Repository
public class JobRepository {

    private static final String COLUMNS = """
            id, type, status, progress, message, params::text AS params, result::text AS result, error,
            created_by_name, attempts, created_at, started_at, finished_at""";

    public record Claimed(UUID id, String type, String params, String createdBy, String createdByName, int attempts,
                          int maxAttempts) {
    }

    public record Expired(UUID id, String status) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JobRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(UUID id, String type, String paramsJson, Actor actor, String idempotencyKey, int maxAttempts) {
        jdbc.sql("""
                        INSERT INTO analysis_job (id, type, status, params, message, created_by, created_by_name,
                                                  idempotency_key, max_attempts)
                        VALUES (:id, :type, 'QUEUED', CAST(:params AS jsonb), '대기 중', :by, :byName, :key, :max)""")
                .param("id", id)
                .param("type", type)
                .param("params", paramsJson)
                .param("by", actor.subject())
                .param("byName", actor.username())
                .param("key", idempotencyKey)
                .param("max", maxAttempts)
                .update();
    }

    public Optional<JobView> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM analysis_job WHERE id = :id")
                .param("id", id)
                .query(this::view)
                .optional();
    }

    public Optional<String> owner(UUID id) {
        return jdbc.sql("SELECT created_by FROM analysis_job WHERE id = :id").param("id", id)
                .query(String.class).optional();
    }

    public Optional<JobView> findByIdempotencyKey(String createdBy, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM analysis_job WHERE created_by = :by AND idempotency_key = :key")
                .param("by", createdBy)
                .param("key", key)
                .query(this::view)
                .optional();
    }

    /** createdBy가 null이면 전체. 결과 본문은 목록에서 빼서 응답을 가볍게 한다. */
    public List<JobView> list(String createdBy, String type, int limit) {
        return jdbc.sql("""
                        SELECT id, type, status, progress, message, params::text AS params, NULL AS result, error,
                               created_by_name, attempts, created_at, started_at, finished_at
                          FROM analysis_job
                         WHERE (CAST(:by AS varchar) IS NULL OR created_by = CAST(:by AS varchar))
                           AND (CAST(:type AS varchar) IS NULL OR type = CAST(:type AS varchar))
                         ORDER BY created_at DESC LIMIT :limit""")
                .param("by", createdBy)
                .param("type", type)
                .param("limit", limit)
                .query(this::view)
                .list();
    }

    public int countActive(String createdBy) {
        return jdbc.sql("SELECT count(*) FROM analysis_job WHERE created_by = :by AND status IN ('QUEUED', 'RUNNING')")
                .param("by", createdBy)
                .query(Integer.class)
                .single();
    }

    public long countQueued() {
        return jdbc.sql("SELECT count(*) FROM analysis_job WHERE status = 'QUEUED'").query(Long.class).single();
    }

    /** 실행 가능한 가장 오래된 Job 하나를 원자적으로 가져온다. 다른 노드가 잠근 행은 건너뛴다(SKIP LOCKED). */
    public Optional<Claimed> claim(String node, Duration lease) {
        return jdbc.sql("""
                        UPDATE analysis_job j
                           SET status = 'RUNNING', locked_by = :node,
                               locked_until = now() + make_interval(secs => :leaseSec),
                               attempts = attempts + 1, started_at = coalesce(started_at, now()),
                               message = '실행 시작', updated_at = now()
                         WHERE j.id = (SELECT id FROM analysis_job
                                        WHERE status = 'QUEUED' AND run_after <= now()
                                        ORDER BY run_after, created_at
                                        FOR UPDATE SKIP LOCKED LIMIT 1)
                        RETURNING j.id, j.type, j.params::text AS params, j.created_by, j.created_by_name,
                                  j.attempts, j.max_attempts""")
                .param("node", node)
                .param("leaseSec", (double) lease.toSeconds())
                .query((rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("params"), rs.getString("created_by"), rs.getString("created_by_name"),
                        rs.getInt("attempts"), rs.getInt("max_attempts")))
                .optional();
    }

    /**
     * 진행률 기록.
     * @return 취소 요청 여부. 임대를 잃었으면 empty
     */
    public Optional<Boolean> progress(UUID id, String node, int percent, String message) {
        return jdbc.sql("""
                        UPDATE analysis_job SET progress = :progress, message = :message, updated_at = now()
                         WHERE id = :id AND locked_by = :node AND status = 'RUNNING'
                        RETURNING cancel_requested""")
                .param("progress", percent)
                .param("message", message)
                .param("id", id)
                .param("node", node)
                .query(Boolean.class)
                .optional();
    }

    public boolean complete(UUID id, String node, JobStatus status, String resultJson, String error) {
        return jdbc.sql("""
                        UPDATE analysis_job
                           SET status = CAST(:status AS varchar),
                               progress = CASE WHEN CAST(:status AS varchar) = 'SUCCEEDED' THEN 100 ELSE progress END,
                               message = :message, result = CAST(:result AS jsonb), error = :error,
                               finished_at = now(), updated_at = now(), locked_by = NULL, locked_until = NULL
                         WHERE id = :id AND locked_by = :node AND status = 'RUNNING'""")
                .param("status", status.name())
                .param("message", switch (status) {
                    case SUCCEEDED -> "완료";
                    case CANCELLED -> "취소됨";
                    default -> "실패";
                })
                .param("result", resultJson)
                .param("error", error)
                .param("id", id)
                .param("node", node)
                .update() == 1;
    }

    /** 일시적 오류: 재시도 한도 안이면 지연 후 다시 대기열로 보낸다. */
    public boolean retryLater(UUID id, String node, Duration delay, String error) {
        return jdbc.sql("""
                        UPDATE analysis_job
                           SET status = 'QUEUED', run_after = now() + make_interval(secs => :delaySec),
                               message = '일시 오류로 재시도 대기', error = :error, locked_by = NULL,
                               locked_until = NULL, updated_at = now()
                         WHERE id = :id AND locked_by = :node AND status = 'RUNNING'""")
                .param("delaySec", (double) delay.toSeconds())
                .param("error", error)
                .param("id", id)
                .param("node", node)
                .update() == 1;
    }

    /**
     * 실행 중인 Job들의 임대를 연장한다.
     * @return 취소가 요청된 Job id
     */
    public List<UUID> extend(String node, Collection<UUID> ids, Duration lease) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        UPDATE analysis_job SET locked_until = now() + make_interval(secs => :leaseSec)
                         WHERE id IN (:ids) AND locked_by = :node AND status = 'RUNNING'
                        RETURNING id, cancel_requested""")
                .param("leaseSec", (double) lease.toSeconds())
                .param("ids", ids)
                .param("node", node)
                .query((rs, i) -> rs.getBoolean("cancel_requested") ? rs.getObject("id", UUID.class) : null)
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 종료 중인 노드가 끝내지 못한 Job을 다른 노드가 이어받도록 돌려놓는다(시도 횟수는 되돌린다). */
    public int release(String node, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE analysis_job
                           SET status = 'QUEUED', attempts = greatest(attempts - 1, 0), locked_by = NULL,
                               locked_until = NULL, message = '노드 종료로 재대기', updated_at = now()
                         WHERE id IN (:ids) AND locked_by = :node AND status = 'RUNNING'""")
                .param("ids", ids)
                .param("node", node)
                .update();
    }

    /** 임대가 만료된 Job(노드 중단)을 재시도 대기열로 돌리거나, 한도를 넘었으면 실패 처리한다. */
    public List<Expired> reapExpired() {
        return jdbc.sql("""
                        UPDATE analysis_job
                           SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'QUEUED' END,
                               error = CASE WHEN attempts >= max_attempts
                                            THEN '작업 노드가 응답하지 않아 재시도 한도(' || max_attempts || '회)를 넘었습니다.'
                                            ELSE error END,
                               message = CASE WHEN attempts >= max_attempts THEN '실패' ELSE '노드 응답 없음, 재대기' END,
                               finished_at = CASE WHEN attempts >= max_attempts THEN now() END,
                               run_after = now() + make_interval(secs => 5 * attempts),
                               locked_by = NULL, locked_until = NULL, updated_at = now()
                         WHERE status = 'RUNNING' AND locked_until < now()
                        RETURNING id, status""")
                .query((rs, i) -> new Expired(rs.getObject("id", UUID.class), rs.getString("status")))
                .list();
    }

    /**
     * 대기 중이면 바로 취소하고, 실행 중이면 취소 요청을 표시한다(작업 노드가 다음 진행률 보고 때 멈춘다).
     * @return 갱신 후 상태
     */
    public Optional<String> cancel(UUID id) {
        return jdbc.sql("""
                        UPDATE analysis_job
                           SET status = CASE WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE status END,
                               finished_at = CASE WHEN status = 'QUEUED' THEN now() ELSE finished_at END,
                               message = CASE WHEN status = 'QUEUED' THEN '취소됨' ELSE '취소 요청됨' END,
                               cancel_requested = true, updated_at = now()
                         WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
                        RETURNING status""")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    public int purgeFinished(Duration retention) {
        return jdbc.sql("DELETE FROM analysis_job WHERE finished_at < now() - make_interval(secs => :sec)")
                .param("sec", (double) retention.toSeconds())
                .update();
    }

    private JobView view(ResultSet rs, int row) throws SQLException {
        return new JobView(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("status"),
                rs.getInt("progress"), rs.getString("message"), json(rs.getString("params")),
                json(rs.getString("result")), rs.getString("error"), rs.getString("created_by_name"),
                rs.getInt("attempts"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
    }

    private JsonNode json(String s) {
        return s == null ? null : mapper.readTree(s);
    }

    private static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
