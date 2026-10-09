package io.twinpilot.api.common;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 키별 SSE 연결 묶음. 끊긴 연결은 전송 실패 시 자동으로 정리한다.
 * 프록시·로드밸런서 유휴 타임아웃(ALB 기본 60초)에 끊기지 않도록 주기적으로 주석 이벤트를 보낸다.
 */
public class SseHub<K> {

    private final Map<K, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public SseHub(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public SseEmitter register(K key) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        List<SseEmitter> list = emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> remove(key, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    private void remove(K key, SseEmitter emitter) {
        emitters.computeIfPresent(key, (k, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }

    /** 단일 연결에 보낸다. 실패하면 false. */
    public static boolean send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    public void send(K key, String event, Object data) {
        List<SseEmitter> list = emitters.get(key);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, event, data)) {
                remove(key, emitter);
            }
        }
    }

    public void sendAndComplete(K key, String event, Object data) {
        List<SseEmitter> list = emitters.remove(key);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(emitter, event, data);
            emitter.complete();
        }
    }

    public void heartbeat() {
        emitters.forEach((key, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException e) {
                    remove(key, emitter);
                }
            }
        });
    }

    public Set<K> keys() {
        return emitters.keySet();
    }

    public boolean has(K key) {
        return emitters.containsKey(key);
    }

    public int size() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }

    public void completeAll() {
        emitters.values().forEach(list -> list.forEach(SseEmitter::complete));
        emitters.clear();
    }
}
