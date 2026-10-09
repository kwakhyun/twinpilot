package io.twinpilot.api.job;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.common.PgNotificationListener;
import io.twinpilot.api.common.SseHub;

/**
 * Job 진행 이벤트를 SSE로 전달한다.
 * Job은 어느 레플리카에서든 실행될 수 있으므로 진행 이벤트를 NOTIFY로 방송하고,
 * 각 레플리카는 자기에게 연결된 SSE 구독자에게만 전달한다. 최종 이벤트는 DB에서 결과를 읽어 보낸다.
 */
@Component
public class JobEvents {

    static final String CHANNEL = "tp_job_events";
    static final String QUEUED_CHANNEL = "tp_job_queued";

    private final SseHub<UUID> hub = new SseHub<>(30 * 60 * 1000L);
    private final PgNotificationListener listener;
    private final JobRepository repository;
    private final ObjectMapper mapper;

    public JobEvents(PgNotificationListener listener, JobRepository repository, ObjectMapper mapper) {
        this.listener = listener;
        this.repository = repository;
        this.mapper = mapper;
        listener.on(CHANNEL, this::onNotification);
        listener.onReconnect(this::resync);
    }

    public void progress(UUID id, int percent, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id.toString());
        payload.put("event", "progress");
        payload.put("progress", percent);
        payload.put("message", truncate(message, 300));
        listener.notify(CHANNEL, mapper.writeValueAsString(payload));
    }

    /** 최종 상태 변경 알림. 결과 본문은 페이로드 한도 때문에 싣지 않는다. */
    public void terminal(UUID id) {
        listener.notify(CHANNEL, mapper.writeValueAsString(Map.of("id", id.toString(), "event", "terminal")));
    }

    public void queued() {
        listener.notify(QUEUED_CHANNEL, "1");
    }

    public SseEmitter subscribe(JobView job) {
        SseEmitter emitter = hub.register(job.id());
        SseHub.send(emitter, "progress", progressData(job));
        if (job.finished()) {
            finish(job.id());
            return emitter;
        }
        // 등록 직전에 끝났을 수 있으므로 한 번 더 확인한다.
        repository.find(job.id()).filter(JobView::finished).ifPresent(v -> finish(v.id()));
        return emitter;
    }

    private void onNotification(String payload) {
        JsonNode node = mapper.readTree(payload);
        UUID id = UUID.fromString(node.path("id").asString());
        if (!hub.has(id)) {
            return;
        }
        if ("terminal".equals(node.path("event").asString())) {
            finish(id);
        } else {
            hub.send(id, "progress", Map.of("progress", node.path("progress").asInt(),
                    "message", node.path("message").asString(), "status", "RUNNING"));
        }
    }

    private void finish(UUID id) {
        repository.find(id).ifPresent(view -> hub.sendAndComplete(id, view.terminalEvent(), view));
    }

    /** LISTEN 재연결 사이에 놓친 이벤트를 메우기 위해 구독 중인 Job 상태를 다시 읽는다. */
    private void resync() {
        for (UUID id : hub.keys()) {
            repository.find(id).ifPresent(view -> {
                if (view.finished()) {
                    hub.sendAndComplete(id, view.terminalEvent(), view);
                } else {
                    hub.send(id, "progress", progressData(view));
                }
            });
        }
    }

    private static Map<String, Object> progressData(JobView job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("progress", job.progress());
        data.put("message", job.message() == null ? "" : job.message());
        data.put("status", job.status());
        return data;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void heartbeat() {
        hub.heartbeat();
    }

    public int subscribers() {
        return hub.size();
    }

    @EventListener(ContextClosedEvent.class)
    public void closeStreams() {
        hub.completeAll();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
