package io.twinpilot.api.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.security.Actor;

/**
 * 감사 로그 기록·조회.
 * <ul>
 *   <li>트랜잭션 안에서 부르면 본 작업과 같은 트랜잭션으로 기록한다. 기록에 실패하면 예외를 던져 본 작업도 롤백한다
 *       (변경과 감사 기록이 함께 남거나 함께 없다).</li>
 *   <li>DB 트랜잭션으로 묶을 수 없는 작업(파일 저장 등) 뒤에 부르면 기록 실패가 본 작업을 막지 않는다. 대신 감사 내용 전체를
 *       구조화 로그로 남기고 실패 수를 센다(경보 AuditWriteFailures).</li>
 * </ul>
 * 클라이언트 IP는 프록시가 넘긴 X-Forwarded-For를 서버가 반영한 값(forward-headers-strategy)이다.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public record AuditEntry(long id, Instant ts, String actorSub, String actorName, String action, String targetType,
                             String targetId, JsonNode details, String clientIp, String traceId) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final ObjectProvider<Tracer> tracer;
    private final Counter failures;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper, ObjectProvider<Tracer> tracer, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tracer = tracer;
        this.failures = Counter.builder("twinpilot.audit.write.failures")
                .description("감사 로그를 DB에 쓰지 못하고 로그로 대신 남긴 수").register(meters);
    }

    public void record(Actor actor, String action, String targetType, String targetId, Map<String, ?> details) {
        boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            jdbc.sql("""
                            INSERT INTO audit_log (actor_sub, actor_name, action, target_type, target_id, details,
                                                   client_ip, trace_id)
                            VALUES (:sub, :name, :action, :type, :target, CAST(:details AS jsonb), :ip, :trace)""")
                    .param("sub", actor.subject())
                    .param("name", actor.username())
                    .param("action", action)
                    .param("type", targetType)
                    .param("target", targetId)
                    .param("details", mapper.writeValueAsString(details))
                    .param("ip", clientIp())
                    .param("trace", traceId())
                    .update();
        } catch (RuntimeException e) {
            if (inTransaction) {
                throw e;
            }
            failures.increment();
            log.error("감사 로그 DB 기록 실패(로그로 대신 보존) actor={} action={} target={}/{} details={}: {}",
                    actor.subject(), action, targetType, targetId, safeJson(details), e.toString());
        }
    }

    private String safeJson(Map<String, ?> details) {
        try {
            return mapper.writeValueAsString(details);
        } catch (RuntimeException e) {
            return String.valueOf(details);
        }
    }

    public List<AuditEntry> search(String action, String actor, int limit) {
        return jdbc.sql("""
                        SELECT id, ts, actor_sub, actor_name, action, target_type, target_id, details::text AS details,
                               client_ip, trace_id
                          FROM audit_log
                         WHERE (CAST(:action AS varchar) IS NULL OR action = CAST(:action AS varchar))
                           AND (CAST(:actor AS varchar) IS NULL OR actor_name = CAST(:actor AS varchar)
                                OR actor_sub = CAST(:actor AS varchar))
                         ORDER BY ts DESC, id DESC LIMIT :limit""")
                .param("action", action)
                .param("actor", actor)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, i) -> new AuditEntry(rs.getLong("id"), rs.getTimestamp("ts").toInstant(),
                        rs.getString("actor_sub"), rs.getString("actor_name"), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"), mapper.readTree(rs.getString("details")),
                        rs.getString("client_ip"), rs.getString("trace_id")))
                .list();
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }

    private String traceId() {
        Tracer t = tracer.getIfAvailable();
        if (t == null || t.currentSpan() == null) {
            return null;
        }
        return t.currentSpan().context().traceId();
    }
}
