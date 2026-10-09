package io.twinpilot.api.sensor;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.twinpilot.api.sensor.SensorIngestService.Reading;
import io.twinpilot.api.sensor.SensorIngestService.Result;

@RestController
@RequestMapping("/api/v1/sensors")
public class SensorController {

    private final SensorHub hub;
    private final SensorIngestService ingest;
    private final JdbcClient jdbc;

    public SensorController(SensorHub hub, SensorIngestService ingest, JdbcClient jdbc) {
        this.hub = hub;
        this.ingest = ingest;
        this.jdbc = jdbc;
    }

    public record SensorStatus(int total, long alerts, Instant lastReading, List<SensorReading> readings) {
    }

    @GetMapping
    public SensorStatus latest(@RequestParam(required = false) String kind,
                               @RequestParam(defaultValue = "false") boolean alertsOnly) {
        List<SensorReading> all = hub.snapshot().stream()
                .filter(r -> kind == null || r.kind().equals(kind))
                .toList();
        List<SensorReading> alerts = all.stream()
                .filter(r -> r.status().equals("ALERT") || r.status().equals("BAD") || r.status().equals("VERY_BAD"))
                .toList();
        return new SensorStatus(all.size(), alerts.size(), hub.lastReading(), alertsOnly ? alerts : all);
    }

    /** 실시간 스트림(SSE). 이벤트 이름: readings */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }

    public record IngestRequest(@NotNull @Size(min = 1) List<@Valid Reading> readings) {
    }

    /** 측정값 수집(INGEST 역할). 잘못된 건은 건별로 거부하고 나머지는 저장한다. */
    @PostMapping("/readings")
    public ResponseEntity<Result> ingest(@Valid @RequestBody IngestRequest request) {
        return ResponseEntity.accepted().body(ingest.ingest(request.readings()));
    }

    public record HistoryPoint(Instant ts, String status, @JsonRawValue String value) {
    }

    @GetMapping("/{sensorId}/history")
    public List<HistoryPoint> history(@PathVariable int sensorId,
                                      @RequestParam(defaultValue = "60") int limit,
                                      @RequestParam(required = false) Instant from) {
        return jdbc.sql("""
                        SELECT ts, status, value::text AS value FROM sensor_reading
                         WHERE sensor_id = :id
                           AND ts >= coalesce(CAST(:from AS timestamptz), now() - interval '1 day')
                         ORDER BY ts DESC LIMIT :limit""")
                .param("id", sensorId)
                .param("from", from == null ? null : java.sql.Timestamp.from(from))
                .param("limit", Math.clamp(limit, 1, 5000))
                .query((rs, i) -> new HistoryPoint(rs.getTimestamp("ts").toInstant(), rs.getString("status"),
                        rs.getString("value")))
                .list();
    }
}
