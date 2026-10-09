package io.twinpilot.api.job;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.twinpilot.api.common.PgNotificationListener;
import io.twinpilot.api.config.TwinPilotProperties;
import io.twinpilot.api.security.Actor;

/**
 * 작업 노드. 레플리카마다 concurrency개의 가상 스레드가 대기열에서 Job을 가져와 실행한다.
 * - 새 Job 알림(NOTIFY)을 받으면 바로 깨어나고, 알림을 놓쳐도 pollInterval마다 확인한다.
 * - 실행 중에는 lease/3 간격으로 임대를 연장한다. 노드가 죽으면 임대 만료 후 다른 노드가 이어받는다.
 * - 종료 신호를 받으면 새 Job을 받지 않고, 유예 시간 안에 끝나지 않은 Job은 대기열로 돌려놓는다.
 */
@Component
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(20);

    private final JobRepository repository;
    private final JobEvents events;
    private final Map<String, JobHandler> handlers;
    private final ObjectMapper mapper;
    private final TwinPilotProperties.Jobs config;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final String node;
    private final Map<UUID, Running> running = new ConcurrentHashMap<>();
    private final Object signal = new Object();
    private final AtomicLong queuedGauge = new AtomicLong();
    private volatile boolean active;
    private Thread[] threads = new Thread[0];

    private static final class Running {
        final Thread thread;
        volatile boolean cancelRequested;

        Running(Thread thread) {
            this.thread = thread;
        }
    }

    public JobWorker(JobRepository repository, JobEvents events, java.util.List<JobHandler> handlers,
                     ObjectMapper mapper, TwinPilotProperties props, PgNotificationListener listener,
                     ObservationRegistry observations, MeterRegistry meters) {
        this.repository = repository;
        this.events = events;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.mapper = mapper;
        this.config = props.jobs();
        this.observations = observations;
        this.meters = meters;
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        this.node = host + ":" + UUID.randomUUID().toString().substring(0, 8);
        listener.on(JobEvents.QUEUED_CHANNEL, payload -> wake());
        Gauge.builder("twinpilot.jobs.running", running, Map::size).description("이 노드에서 실행 중인 Job").register(meters);
        Gauge.builder("twinpilot.jobs.queued", queuedGauge, AtomicLong::get).description("대기열 길이(전체)").register(meters);
    }

    public String node() {
        return node;
    }

    @Override
    public void start() {
        active = true;
        threads = new Thread[Math.max(1, config.concurrency())];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().name("job-worker-" + i).start(this::loop);
        }
        log.info("작업 노드 시작: node={}, concurrency={}, handlers={}", node, threads.length, handlers.keySet());
    }

    @Override
    public void stop() {
        active = false;
        wake();
        long deadline = System.nanoTime() + SHUTDOWN_GRACE.toNanos();
        while (!running.isEmpty() && System.nanoTime() < deadline) {
            sleep(200);
        }
        if (!running.isEmpty()) {
            Set<UUID> left = Set.copyOf(running.keySet());
            int released = repository.release(node, left);
            log.warn("종료 유예 시간 초과: 실행 중이던 Job {}개를 대기열로 돌려놓았습니다({}).", released, left);
            running.values().forEach(r -> r.thread.interrupt());
        }
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    /** 웹 서버보다 먼저 멈추고(진행 중 요청 처리 후) 나중에 시작한다. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2048;
    }

    void wake() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    private void loop() {
        while (active) {
            try {
                var claimed = repository.claim(node, config.lease());
                if (claimed.isPresent()) {
                    execute(claimed.get());
                    continue;
                }
            } catch (RuntimeException e) {
                log.warn("Job 가져오기 실패: {}", e.toString());
                sleep(1000);
            }
            synchronized (signal) {
                if (!active) {
                    break;
                }
                try {
                    signal.wait(config.pollInterval().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void execute(JobRepository.Claimed job) {
        JobHandler handler = handlers.get(job.type());
        Running state = new Running(Thread.currentThread());
        running.put(job.id(), state);
        MDC.put("jobId", job.id().toString());
        long started = System.nanoTime();
        JobStatus outcome = JobStatus.FAILED;
        try {
            if (handler == null) {
                throw new IllegalArgumentException("이 노드가 처리할 수 없는 Job 유형: " + job.type());
            }
            Actor actor = new Actor(job.createdBy(), job.createdByName(), Set.of(), false);
            JsonNode params = mapper.readTree(job.params() == null ? "{}" : job.params());
            Progress progress = (percent, message) -> report(job.id(), state, percent, message);
            Object result = Observation.createNotStarted("twinpilot.job", observations)
                    .lowCardinalityKeyValue("job.type", job.type())
                    .highCardinalityKeyValue("job.id", job.id().toString())
                    .observeChecked(() -> handler.run(params, actor, progress));
            outcome = finish(job, JobStatus.SUCCEEDED, mapper.writeValueAsString(result), null);
        } catch (JobCancelledException e) {
            outcome = finish(job, JobStatus.CANCELLED, null, e.getMessage());
        } catch (LeaseLostException e) {
            log.warn("Job {} 임대 상실, 결과를 버립니다: {}", job.id(), e.getMessage());
            outcome = null;
        } catch (TransientDataAccessException | CannotGetJdbcConnectionException e) {
            if (job.attempts() < job.maxAttempts()
                    && repository.retryLater(job.id(), node, Duration.ofSeconds(10L * job.attempts()), e.getMessage())) {
                log.warn("Job {} 일시 오류, 재시도 예정({}회차): {}", job.id(), job.attempts(), e.toString());
                outcome = JobStatus.QUEUED;
            } else {
                outcome = finish(job, JobStatus.FAILED, null, message(e));
            }
        } catch (Throwable e) {
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
                log.info("Job {} 실패(입력·상태 오류): {}", job.id(), e.getMessage());
            } else {
                log.error("Job {} 실패", job.id(), e);
            }
            outcome = finish(job, JobStatus.FAILED, null, message(e));
        } finally {
            running.remove(job.id());
            MDC.remove("jobId");
            if (outcome != null) {
                Timer.builder("twinpilot.jobs.duration").tag("type", job.type()).tag("status", outcome.name())
                        .register(meters).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
        }
    }

    private JobStatus finish(JobRepository.Claimed job, JobStatus status, String result, String error) {
        if (repository.complete(job.id(), node, status, result, error)) {
            events.terminal(job.id());
            Counter.builder("twinpilot.jobs.completed").tag("type", job.type()).tag("status", status.name())
                    .register(meters).increment();
            return status;
        }
        log.warn("Job {} 결과 저장 생략: 임대를 잃었거나 이미 끝났습니다.", job.id());
        return null;
    }

    private void report(UUID id, Running state, int percent, String message) {
        if (state.cancelRequested) {
            throw new JobCancelledException();
        }
        Boolean cancel = repository.progress(id, node, Math.clamp(percent, 0, 99), message)
                .orElseThrow(() -> new LeaseLostException("진행률 기록 실패"));
        if (cancel) {
            throw new JobCancelledException();
        }
        events.progress(id, percent, message);
    }

    /** 실행 중인 Job의 임대를 연장하고 취소 요청을 전달한다. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)   // 임대(기본 60초)의 1/4 간격
    public void heartbeat() {
        if (running.isEmpty()) {
            return;
        }
        try {
            for (UUID id : repository.extend(node, running.keySet(), config.lease())) {
                Running r = running.get(id);
                if (r != null) {
                    r.cancelRequested = true;
                }
            }
        } catch (RuntimeException e) {
            log.warn("임대 연장 실패: {}", e.toString());
        }
    }

    /** 다른 노드가 중단돼 임대가 만료된 Job을 정리한다. 모든 레플리카가 실행해도 결과는 같다. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 10_000)
    public void reap() {
        try {
            for (var expired : repository.reapExpired()) {
                log.warn("임대 만료 Job 처리: {} → {}", expired.id(), expired.status());
                if (JobStatus.valueOf(expired.status()).terminal()) {
                    events.terminal(expired.id());
                } else {
                    events.queued();
                }
            }
            queuedGauge.set(repository.countQueued());
        } catch (RuntimeException e) {
            log.warn("임대 만료 정리 실패: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purge() {
        int n = repository.purgeFinished(config.retention());
        if (n > 0) {
            log.info("보존 기간이 지난 Job 기록 {}건 삭제", n);
        }
    }

    private static String message(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 1000 ? m.substring(0, 1000) : m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
