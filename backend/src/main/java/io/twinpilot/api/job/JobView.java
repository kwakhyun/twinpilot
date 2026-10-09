package io.twinpilot.api.job;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record JobView(UUID id, String type, String status, int progress, String message, JsonNode params,
                      JsonNode result, String error, String createdBy, int attempts, Instant createdAt,
                      Instant startedAt, Instant finishedAt) {

    public boolean finished() {
        return JobStatus.valueOf(status).terminal();
    }

    /** SSE 최종 이벤트 이름: done | failed | cancelled */
    public String terminalEvent() {
        return switch (JobStatus.valueOf(status)) {
            case SUCCEEDED -> "done";
            case CANCELLED -> "cancelled";
            default -> "failed";
        };
    }
}
