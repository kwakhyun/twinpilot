package io.twinpilot.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SingleFlightCacheTest {

    @Test
    void concurrentCallersShareOneComputation() throws Exception {
        var cache = new SingleFlightCache<String, Integer>(Duration.ofSeconds(10), 100);
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                results.add(pool.submit(() -> cache.get("k", () -> {
                    calls.incrementAndGet();
                    await(release);
                    return 42;
                })));
            }
            Thread.sleep(100);
            release.countDown();
            for (Future<Integer> r : results) {
                assertThat(r.get(5, TimeUnit.SECONDS)).isEqualTo(42);
            }
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void expiresAfterTtlAndOnInvalidate() {
        var now = new AtomicLong();
        var cache = new SingleFlightCache<String, Integer>(Duration.ofSeconds(10), 100, now::get);
        var calls = new AtomicInteger();
        cache.get("k", calls::incrementAndGet);
        cache.get("k", calls::incrementAndGet);
        assertThat(calls).hasValue(1);

        now.addAndGet(Duration.ofSeconds(11).toNanos());
        assertThat(cache.get("k", calls::incrementAndGet)).isEqualTo(2);

        cache.invalidateAll();
        assertThat(cache.get("k", calls::incrementAndGet)).isEqualTo(3);
    }

    @Test
    void failuresAreNotCached() {
        var cache = new SingleFlightCache<String, Integer>(Duration.ofSeconds(10), 100);
        assertThatThrownBy(() -> cache.get("k", () -> {
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class).hasMessage("db down");
        assertThat(cache.get("k", () -> 7)).isEqualTo(7);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void sizeStaysBounded() {
        var cache = new SingleFlightCache<Integer, Integer>(Duration.ofSeconds(10), 16);
        for (int i = 0; i < 1_000; i++) {
            int key = i;
            cache.get(key, () -> key);
            assertThat(cache.size()).isLessThanOrEqualTo(16);
        }
    }
}
