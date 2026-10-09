package io.twinpilot.api.seed;

import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import io.twinpilot.api.config.TwinPilotProperties;

/**
 * 비어 있는 DB에 합성(가상) 데모 데이터를 적재하고 화재위험도를 계산한다.
 * 운영 환경에서는 SEED_ENABLED=false로 끄고 실제 데이터를 적재한다.
 */
@Component
@Order(0)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final long SEED_LOCK = 7_420_001L;

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TwinPilotProperties props;

    public DemoDataSeeder(JdbcClient jdbc, DataSource dataSource, TwinPilotProperties props) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seed().enabled()) {
            return;
        }
        // 여러 레플리카가 동시에 떠도 한 번만 적재되도록 세션 advisory lock으로 직렬화한다.
        new JdbcTemplate(dataSource).execute((ConnectionCallback<Void>) conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT pg_advisory_lock(" + SEED_LOCK + ")");
                try {
                    seedIfEmpty(new SingleConnectionDataSource(conn, true));
                } finally {
                    st.execute("SELECT pg_advisory_unlock(" + SEED_LOCK + ")");
                }
            }
            return null;
        });
    }

    private void seedIfEmpty(DataSource locked) {
        JdbcClient client = JdbcClient.create(locked);
        long buildings = client.sql("SELECT count(*) FROM building").query(Long.class).single();
        if (buildings > 0) {
            log.info("시드 생략: 건물 {}동이 이미 있습니다.", buildings);
            return;
        }
        long started = System.currentTimeMillis();
        new ResourceDatabasePopulator(new ClassPathResource("db/seed/seed.sql")).execute(locked);
        int updated = refreshRisk(client);
        log.info("합성 데모 데이터 적재 완료: 건물 {}동, {}ms", updated, System.currentTimeMillis() - started);
    }

    public int refreshRisk() {
        return refreshRisk(jdbc);
    }

    private int refreshRisk(JdbcClient client) {
        var s = props.standards();
        return client.sql("SELECT refresh_building_risk(:speed, :detour)")
                .param("speed", s.speedMetersPerMin())
                .param("detour", s.detourFactor())
                .query(Integer.class)
                .single();
    }
}
