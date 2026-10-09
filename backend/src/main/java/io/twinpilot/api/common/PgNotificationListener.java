package io.twinpilot.api.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL LISTEN/NOTIFY로 레플리카 간 이벤트를 전달한다(별도 메시지 브로커 없이 Job 진행률·센서 갱신을 방송).
 * 전용 커넥션 하나로 채널을 구독하고, 끊기면 다시 연결한 뒤 재연결 콜백을 호출한다(그 사이 놓친 상태를 다시 읽게 한다).
 * NOTIFY는 커밋 시점에 전달되며 페이로드는 8000바이트 미만이어야 하므로 식별자와 작은 값만 싣는다.
 */
@Component
public class PgNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PgNotificationListener.class);

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    private final List<Runnable> reconnectHandlers = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile boolean connected;
    private Thread thread;

    public PgNotificationListener(DataSource dataSource, JdbcClient jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** 채널 구독. 기동 전에 등록해야 한다(빈 생성자에서 호출). */
    public void on(String channel, Consumer<String> handler) {
        handlers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void onReconnect(Runnable handler) {
        reconnectHandlers.add(handler);
    }

    public void notify(String channel, String payload) {
        jdbc.sql("SELECT pg_notify(:channel, :payload)").param("channel", channel).param("payload", payload)
                .query(Object.class).optional();
    }

    public boolean connected() {
        return connected;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("pg-listener").start(this::loop);
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        long backoff = 500;
        boolean first = true;
        while (running) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                try (Statement st = conn.createStatement()) {
                    for (String channel : handlers.keySet()) {
                        st.execute("LISTEN " + channel);
                    }
                }
                connected = true;
                backoff = 500;
                if (!first) {
                    log.info("LISTEN 재연결: 채널 {}", handlers.keySet());
                    reconnectHandlers.forEach(PgNotificationListener::safeRun);
                }
                first = false;
                PGConnection pg = conn.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(1000);
                    if (notifications == null) {
                        // 타임아웃. 커넥션이 살아 있는지 확인한다.
                        if (!conn.isValid(2)) {
                            throw new SQLException("LISTEN 커넥션이 끊겼습니다.");
                        }
                        continue;
                    }
                    for (PGNotification n : notifications) {
                        dispatch(n.getName(), n.getParameter());
                    }
                }
            } catch (SQLException e) {
                connected = false;
                if (!running) {
                    break;
                }
                log.warn("LISTEN 커넥션 오류, {}ms 후 재시도: {}", backoff, e.getMessage());
                sleep(backoff);
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
        connected = false;
    }

    private void dispatch(String channel, String payload) {
        for (Consumer<String> handler : handlers.getOrDefault(channel, List.of())) {
            try {
                handler.accept(payload);
            } catch (RuntimeException e) {
                log.warn("NOTIFY 처리 실패 channel={}: {}", channel, e.toString());
            }
        }
    }

    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("재연결 처리 실패: {}", e.toString());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
