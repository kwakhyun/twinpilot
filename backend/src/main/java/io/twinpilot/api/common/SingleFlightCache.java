package io.twinpilot.api.common;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 짧은 TTL 캐시 + 동시 요청 합치기. 같은 키를 계산 중이면 새로 계산하지 않고 그 결과를 기다린다.
 * 무거운 집계가 동시에 몰려 DB 커넥션 풀을 고갈시키지 않게 한다. 실패한 계산은 캐시하지 않는다.
 * 요청 파라미터가 키라면 항목 수가 maxEntries를 넘지 않게 만료 항목부터 정리한다(그래도 넘으면 모두 비운다).
 */
public final class SingleFlightCache<K, V> {

    private record Entry<V>(CompletableFuture<V> future, long expiresAt) {
        boolean usable(long now) {
            return !future.isDone() || (now < expiresAt && !future.isCompletedExceptionally());
        }
    }

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier clock;

    public SingleFlightCache(Duration ttl, int maxEntries) {
        this(ttl, maxEntries, System::nanoTime);
    }

    SingleFlightCache(Duration ttl, int maxEntries, LongSupplier clock) {
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public V get(K key, Supplier<V> loader) {
        var mine = new CompletableFuture<V>();
        long now = clock.getAsLong();
        if (entries.size() >= maxEntries && !entries.containsKey(key)) {
            entries.values().removeIf(e -> !e.usable(now));
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        Entry<V> entry = entries.compute(key, (k, old) -> old != null && old.usable(now) ? old : new Entry<>(mine, now + ttlNanos));
        if (entry.future() == mine) {
            try {
                mine.complete(loader.get());
            } catch (RuntimeException | Error e) {
                entries.remove(key, entry);
                mine.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return entry.future().join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /** 데이터가 바뀌면 비운다(진행 중인 계산은 끝까지 기다린 요청에만 쓰인다). */
    public void invalidateAll() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
