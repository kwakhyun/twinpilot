package io.twinpilot.api.sensor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.common.PgNotificationListener;
import io.twinpilot.api.common.SseHub;

/**
 * 센서 최신 상태를 레플리카마다 캐시하고 SSE로 방송한다.
 * 어느 레플리카가 수집하든 NOTIFY를 받으면 sensor_latest를 다시 읽는다(짧은 간격의 알림은 하나로 합친다).
 * 알림을 놓쳐도 10초마다 다시 읽어 상태가 오래 어긋나지 않게 한다.
 */
@Component
public class SensorHub {

    private static final Logger log = LoggerFactory.getLogger(SensorHub.class);
    private static final String KEY = "all";
    private static final TypeReference<Map<String, Double>> VALUES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final SseHub<String> hub = new SseHub<>(30 * 60 * 1000L);
    private final AtomicBoolean reloadPending = new AtomicBoolean();
    private volatile List<SensorReading> snapshot = List.of();
    private volatile Instant lastReading;

    public SensorHub(JdbcClient jdbc, ObjectMapper mapper, PgNotificationListener listener, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        listener.on(SensorIngestService.CHANNEL, payload -> scheduleReload());
        listener.onReconnect(this::reloadAndBroadcast);
        Gauge.builder("twinpilot.sse.subscribers", hub, SseHub::size).tag("stream", "sensors").register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        reload();
    }

    /** 알림이 몰려도 250ms 안의 것은 한 번만 다시 읽는다. */
    private void scheduleReload() {
        if (reloadPending.compareAndSet(false, true)) {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reloadPending.set(false);
                reloadAndBroadcast();
            });
        }
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void periodicReload() {
        reloadAndBroadcast();
    }

    void reloadAndBroadcast() {
        try {
            reload();
            hub.send(KEY, "readings", snapshot);
        } catch (RuntimeException e) {
            log.warn("센서 상태 갱신 실패: {}", e.toString());
        }
    }

    void reload() {
        snapshot = jdbc.sql("""
                        SELECT s.id, s.code, s.kind, s.facility_id, s.route_id, ST_X(l.geom) AS lon, ST_Y(l.geom) AS lat,
                               l.value::text AS value, l.status, l.ts
                          FROM sensor_latest l JOIN sensor s ON s.id = l.sensor_id
                         ORDER BY s.id""")
                .query((rs, i) -> new SensorReading(rs.getInt("id"), rs.getString("code"), rs.getString("kind"),
                        (Integer) rs.getObject("facility_id"), (Integer) rs.getObject("route_id"),
                        rs.getDouble("lon"), rs.getDouble("lat"), mapper.readValue(rs.getString("value"), VALUES),
                        rs.getString("status"), rs.getTimestamp("ts").toInstant()))
                .list();
        lastReading = snapshot.stream().map(SensorReading::ts).max(Instant::compareTo).orElse(null);
    }

    public List<SensorReading> snapshot() {
        return snapshot;
    }

    /** 가장 최근 측정 시각. 수집이 멈췄는지 판단하는 데 쓴다. */
    public Instant lastReading() {
        return lastReading;
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = hub.register(KEY);
        SseHub.send(emitter, "readings", snapshot);
        return emitter;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void heartbeat() {
        hub.heartbeat();
    }

    public int subscriberCount() {
        return hub.size();
    }

    /** 종료 신호를 받으면 SSE 연결을 먼저 닫는다(graceful shutdown이 열린 스트림을 기다리지 않게). */
    @EventListener(ContextClosedEvent.class)
    public void closeStreams() {
        hub.completeAll();
    }
}
