package io.twinpilot.api.sensor;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.twinpilot.api.config.TwinPilotProperties;

/**
 * sensor_reading 일 단위 파티션 관리(UTC 기준).
 * - 오늘부터 3일 뒤까지 파티션을 미리 만든다. DEFAULT 파티션에 이미 그 날짜 행이 있으면 옮긴 뒤 붙인다.
 * - 보존 기간이 지난 파티션은 DROP한다(행 단위 DELETE보다 빠르고 테이블 팽창이 없다).
 * 여러 레플리카가 동시에 실행해도 트랜잭션 advisory lock으로 한 번에 하나만 DDL을 수행한다.
 */
@Component
public class SensorPartitionManager {

    private static final Logger log = LoggerFactory.getLogger(SensorPartitionManager.class);
    private static final long LOCK = 7_420_003L;
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TwinPilotProperties.Sensors config;

    public SensorPartitionManager(JdbcClient jdbc, TransactionTemplate tx, TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.config = props.sensors();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 5 * * * *", zone = "UTC")
    public void maintain() {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            for (int d = 0; d <= 3; d++) {
                ensure(today.plusDays(d));
            }
            dropOlderThan(today.minusDays(Math.max(1, config.retentionDays())));
        } catch (RuntimeException e) {
            log.error("센서 파티션 관리 실패: {}", e.toString());
        }
    }

    void ensure(LocalDate day) {
        String name = "sensor_reading_p" + day.format(SUFFIX);
        tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", LOCK).query(Object.class).optional();
            boolean exists = jdbc.sql("SELECT to_regclass(:n) IS NOT NULL").param("n", name).query(Boolean.class).single();
            if (exists) {
                return;
            }
            String from = day.toString() + " 00:00:00+00";
            String to = day.plusDays(1).toString() + " 00:00:00+00";
            jdbc.sql("CREATE TABLE " + name + " (LIKE sensor_reading INCLUDING DEFAULTS INCLUDING CONSTRAINTS)").update();
            int moved = jdbc.sql("""
                            WITH moved AS (DELETE FROM sensor_reading_default
                                            WHERE ts >= CAST(:from AS timestamptz) AND ts < CAST(:to AS timestamptz)
                                            RETURNING *)
                            INSERT INTO %s SELECT * FROM moved""".formatted(name))
                    .param("from", from).param("to", to).update();
            jdbc.sql("ALTER TABLE sensor_reading ATTACH PARTITION " + name
                    + " FOR VALUES FROM ('" + from + "') TO ('" + to + "')").update();
            log.info("센서 파티션 생성: {} (DEFAULT에서 {}건 이동)", name, moved);
        });
    }

    void dropOlderThan(LocalDate cutoff) {
        List<String> parts = jdbc.sql("""
                        SELECT c.relname FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'sensor_reading' AND c.relname ~ '^sensor_reading_p[0-9]{8}$'""")
                .query(String.class).list();
        String limit = "sensor_reading_p" + cutoff.format(SUFFIX);
        for (String part : parts) {
            if (part.compareTo(limit) < 0) {
                tx.executeWithoutResult(status -> {
                    jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", LOCK).query(Object.class).optional();
                    jdbc.sql("DROP TABLE IF EXISTS " + part).update();
                });
                log.info("보존 기간이 지난 센서 파티션 삭제: {}", part);
            }
        }
        int deleted = jdbc.sql("DELETE FROM sensor_reading_default WHERE ts < CAST(:cutoff AS timestamptz)")
                .param("cutoff", cutoff.toString() + " 00:00:00+00").update();
        if (deleted > 0) {
            log.info("DEFAULT 파티션의 오래된 행 {}건 삭제", deleted);
        }
    }
}
