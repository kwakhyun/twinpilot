package io.twinpilot.api.common;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 테이블 임대 기반 리더 선출. 커넥션을 붙잡지 않으므로 커넥션 풀러(PgBouncer 트랜잭션 모드) 뒤에서도 동작한다.
 * 리더는 TTL 안에 임대를 갱신해야 하며, 갱신이 끊기면 TTL 뒤 다른 레플리카가 넘겨받는다.
 */
@Component
public class LeaderElection {

    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);
    public static final Duration TTL = Duration.ofSeconds(15);

    private final JdbcClient jdbc;
    private final String me = System.getenv().getOrDefault("HOSTNAME", "local") + ":" + UUID.randomUUID().toString().substring(0, 8);
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastRenewed = new ConcurrentHashMap<>();

    public LeaderElection(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 임대를 얻거나 갱신한다. 리더이면 true. */
    public boolean acquire(String name) {
        try {
            boolean leader = jdbc.sql("""
                            INSERT INTO leader_lease (name, holder, until)
                            VALUES (:name, :me, now() + make_interval(secs => :ttl))
                            ON CONFLICT (name) DO UPDATE SET holder = EXCLUDED.holder, until = EXCLUDED.until
                             WHERE leader_lease.holder = EXCLUDED.holder OR leader_lease.until < now()
                            RETURNING holder""")
                    .param("name", name)
                    .param("me", me)
                    .param("ttl", (double) TTL.toSeconds())
                    .query(String.class)
                    .optional()
                    .isPresent();
            if (leader && held.add(name)) {
                log.info("리더 임대 획득: {} ({})", name, me);
            } else if (!leader && held.remove(name)) {
                log.warn("리더 임대 상실: {}", name);
            }
            if (leader) {
                lastRenewed.put(name, System.nanoTime());
            }
            return leader;
        } catch (RuntimeException e) {
            held.remove(name);
            log.warn("리더 임대 확인 실패({}): {}", name, e.toString());
            return false;
        }
    }

    /** 마지막 갱신이 TTL 절반 안이면 리더로 본다(DB를 매번 묻지 않는 빠른 확인). */
    public boolean isLeader(String name) {
        Long at = lastRenewed.get(name);
        return held.contains(name) && at != null && System.nanoTime() - at < TTL.toNanos() / 2;
    }

    public String identity() {
        return me;
    }

    @EventListener(ContextClosedEvent.class)
    public void releaseAll() {
        for (String name : held) {
            try {
                jdbc.sql("DELETE FROM leader_lease WHERE name = :name AND holder = :me")
                        .param("name", name).param("me", me).update();
            } catch (RuntimeException e) {
                log.debug("임대 반납 실패: {}", e.toString());
            }
        }
        held.clear();
    }
}
