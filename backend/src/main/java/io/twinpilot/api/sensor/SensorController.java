package io.twinpilot.api.sensor;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/sensors")
public class SensorController {

    private final SensorSimulator simulator;
    private final JdbcClient jdbc;

    public SensorController(SensorSimulator simulator, JdbcClient jdbc) {
        this.simulator = simulator;
        this.jdbc = jdbc;
    }

    public record SensorStatus(int total, long alerts, List<SensorReading> readings) {
    }

    @GetMapping
    public SensorStatus latest(@RequestParam(required = false) String kind,
                               @RequestParam(defaultValue = "false") boolean alertsOnly) {
        List<SensorReading> all = simulator.snapshot().stream()
                .filter(r -> kind == null || r.kind().equals(kind))
                .toList();
        List<SensorReading> alerts = all.stream()
                .filter(r -> r.status().equals("ALERT") || r.status().equals("BAD") || r.status().equals("VERY_BAD"))
                .toList();
        return new SensorStatus(all.size(), alerts.size(), alertsOnly ? alerts : all);
    }

    /** 실시간 스트림(SSE). 이벤트 이름: readings */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return simulator.subscribe();
    }

    public record HistoryPoint(Instant ts, @JsonRawValue String value) {
    }

    @GetMapping("/{sensorId}/history")
    public List<HistoryPoint> history(@PathVariable int sensorId,
                                      @RequestParam(defaultValue = "60") int limit) {
        return jdbc.sql("""
                        SELECT ts, value::text AS value FROM sensor_reading
                         WHERE sensor_id = :id ORDER BY ts DESC LIMIT :limit""")
                .param("id", sensorId)
                .param("limit", Math.clamp(limit, 1, 1000))
                .query((rs, i) -> new HistoryPoint(rs.getTimestamp("ts").toInstant(), rs.getString("value")))
                .list();
    }
}
