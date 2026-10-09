package io.twinpilot.api.analysis;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.job.Progress;

/**
 * 건물 화재위험도 일괄 재계산.
 * 여러 요청이 겹쳐도 한 번에 하나만 돌도록 트랜잭션 advisory lock으로 직렬화하고,
 * 대량 갱신이므로 요청용 statement_timeout(30초) 대신 트랜잭션 안에서 제한을 늘린다.
 */
@Service
public class RiskService {

    static final long RISK_LOCK = 7_420_002L;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TwinPilotProperties.Standards standards;

    public RiskService(JdbcClient jdbc, TransactionTemplate tx, TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.standards = props.standards();
    }

    public int refresh(Progress progress) {
        progress.report(10, "화재위험도 재계산");
        Integer updated = tx.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '15min'").update();
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", RISK_LOCK).query(Object.class).optional();
            return refreshIn(jdbc);
        });
        progress.report(95, "건물 %d동 갱신".formatted(updated));
        return updated == null ? 0 : updated;
    }

    /** 이미 잡힌 커넥션(시드 적재 중)에서 그대로 계산할 때 쓴다. */
    public int refreshIn(JdbcClient client) {
        return client.sql("SELECT refresh_building_risk(:speed, :detour)")
                .param("speed", standards.speedMetersPerMin())
                .param("detour", standards.detourFactor())
                .query(Integer.class)
                .single();
    }
}
