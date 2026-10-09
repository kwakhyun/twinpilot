package io.twinpilot.api.job;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.audit.AuditService;
import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.security.Actor;
import io.twinpilot.api.security.Role;

/** Job 생성·조회·취소. 실행은 {@link JobWorker}가 맡는다. */
@Service
public class JobQueue {

    private final JobRepository repository;
    private final JobEvents events;
    private final Map<String, JobHandler> handlers;
    private final ObjectMapper mapper;
    private final TwinPilotProperties.Jobs config;
    private final AuditService audit;

    public JobQueue(JobRepository repository, JobEvents events, List<JobHandler> handlers, ObjectMapper mapper,
                    TwinPilotProperties props, AuditService audit) {
        this.repository = repository;
        this.events = events;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.mapper = mapper;
        this.config = props.jobs();
        this.audit = audit;
    }

    public JobHandler handler(String type) {
        JobHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("지원하지 않는 Job 유형: " + type);
        }
        return handler;
    }

    /**
     * Job을 대기열에 넣는다.
     * @param idempotencyKey 같은 키로 다시 요청하면 새로 만들지 않고 기존 Job을 돌려준다
     */
    public JobView submit(String type, Map<String, Object> params, Actor actor, String idempotencyKey) {
        JobHandler handler = handler(type);
        if (!actor.has(handler.requiredRole())) {
            throw new AccessDeniedException(type + " Job은 " + handler.requiredRole() + " 역할이 필요합니다.");
        }
        if (idempotencyKey != null) {
            var existing = repository.findByIdempotencyKey(actor.subject(), idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (handler.countsTowardQuota() && !actor.service()
                && repository.countActive(actor.subject()) >= config.maxActivePerUser()) {
            throw new TooManyJobsException("대기·실행 중인 Job이 %d개입니다. 끝난 뒤 다시 요청하세요."
                    .formatted(config.maxActivePerUser()));
        }
        UUID id = UUID.randomUUID();
        try {
            repository.insert(id, type, mapper.writeValueAsString(params), actor, idempotencyKey, config.maxAttempts());
        } catch (DuplicateKeyException e) {
            // 같은 키로 동시에 들어온 요청: 먼저 저장된 Job을 돌려준다.
            return repository.findByIdempotencyKey(actor.subject(), idempotencyKey).orElseThrow(() -> e);
        }
        audit.record(actor, "JOB_SUBMIT", "job", id.toString(), Map.of("type", type, "params", params));
        events.queued();
        return get(id);
    }

    public JobView get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NoSuchElementException("Job을 찾을 수 없습니다: " + id));
    }

    /** 만든 사람이나 ADMIN만 본다. 다른 사용자의 Job은 있는지도 알리지 않는다(404). */
    public JobView get(UUID id, Actor actor) {
        String owner = repository.owner(id).orElse(null);
        if (owner == null || (!owner.equals(actor.subject()) && !actor.has(Role.ADMIN))) {
            throw new NoSuchElementException("Job을 찾을 수 없습니다: " + id);
        }
        return get(id);
    }

    public List<JobView> list(Actor actor, boolean all, String type, int limit) {
        if (all && !actor.has(Role.ADMIN)) {
            throw new AccessDeniedException("전체 Job 목록은 ADMIN 역할이 필요합니다.");
        }
        return repository.list(all ? null : actor.subject(), type, Math.clamp(limit, 1, 200));
    }

    /** 만든 사람이나 ADMIN만 취소할 수 있다. */
    public JobView cancel(UUID id, Actor actor) {
        String owner = repository.owner(id).orElseThrow(() -> new NoSuchElementException("Job을 찾을 수 없습니다: " + id));
        if (!owner.equals(actor.subject()) && !actor.has(Role.ADMIN)) {
            throw new AccessDeniedException("다른 사용자의 Job은 취소할 수 없습니다.");
        }
        String status = repository.cancel(id)
                .orElseThrow(() -> new IllegalStateException("이미 끝난 Job입니다: " + id));
        audit.record(actor, "JOB_CANCEL", "job", id.toString(), Map.of());
        if (JobStatus.valueOf(status).terminal()) {
            events.terminal(id);
        }
        return get(id);
    }

    public SseEmitter subscribe(UUID id, Actor actor) {
        return events.subscribe(get(id, actor));
    }
}
