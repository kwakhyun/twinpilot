package io.twinpilot.api.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

/**
 * 건물·시설·위험도처럼 집계 캐시에 영향을 주는 데이터가 바뀌었음을 모든 레플리카에 알린다.
 * 이 레플리카의 캐시는 즉시 비우고(쓴 사용자가 바로 다시 읽어도 새 값), 다른 레플리카는 PostgreSQL NOTIFY로 비운다.
 * 알림 연결이 끊겼다 다시 붙으면 놓친 알림이 있을 수 있으므로 한 번 더 비운다.
 */
@Component
public class DataChangeEvents {

    public static final String CHANNEL = "twinpilot_data_changed";

    private final PgNotificationListener listener;
    private final List<Runnable> subscribers = new CopyOnWriteArrayList<>();

    public DataChangeEvents(PgNotificationListener listener) {
        this.listener = listener;
        listener.on(CHANNEL, payload -> fire());
        listener.onReconnect(this::fire);
    }

    public void subscribe(Runnable onChange) {
        subscribers.add(onChange);
    }

    /** 데이터를 바꾼 뒤(커밋 후) 호출한다. */
    public void changed(String what) {
        fire();
        listener.notify(CHANNEL, what);
    }

    private void fire() {
        subscribers.forEach(Runnable::run);
    }
}
