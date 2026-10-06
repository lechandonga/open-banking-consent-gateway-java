package com.github.lechandonga.openbankingconsentgatewayjava.event;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;

import jakarta.annotation.PreDestroy;

/**
 * 异步事件分发器：
 * - 首次投递立即在线程池执行；
 * - 失败后按指数退避 base*2^(n-1)（封顶 backoff-max-ms）安排重试；
 * - 达到 max-attempts 后置为 DEAD，保留最后失败原因；
 * - 每个事件在任一时刻只有一个投递任务，保证不重复入队。
 */
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final EventStore store;
    private final GatewayProperties properties;
    private final CallbackTransport transport;
    private final ObjectMapper objectMapper;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;

    public EventDispatcher(EventStore store, GatewayProperties properties,
                           CallbackTransport transport, ObjectMapper objectMapper) {
        this.store = store;
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.workers = Executors.newFixedThreadPool(
                Math.max(1, properties.getCallback().getWorkerThreads()),
                runnable -> {
                    Thread thread = new Thread(runnable, "callback-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "callback-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void dispatch(BusinessEvent event) {
        workers.submit(() -> attempt(event, 1));
    }

    private void attempt(BusinessEvent event, int attempt) {
        long timestamp = System.currentTimeMillis();
        String body;
        String signature;
        try {
            body = objectMapper.writeValueAsString(new EventEnvelope(event, timestamp));
            signature = SignatureUtil.sign(properties.getCallback().getSigningSecret(),
                    event.getEventId(), timestamp, body);
        } catch (Exception e) {
            fail(event, attempt, "serialization error: " + e.getMessage());
            return;
        }
        try {
            transport.deliver(event, body, timestamp, signature);
            event.markDelivered(attempt);
            log.info("event {} delivered after {} attempt(s)", event.getEventId(), attempt);
        } catch (Exception e) {
            fail(event, attempt, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void fail(BusinessEvent event, int attempt, String error) {
        int maxAttempts = properties.getCallback().getMaxAttempts();
        if (attempt >= maxAttempts) {
            event.markDead(attempt, error);
            log.warn("event {} moved to DEAD after {} attempts, lastError={}", event.getEventId(), attempt, error);
            return;
        }
        long delay = backoffMillis(attempt);
        event.recordFailure(attempt, error, Instant.now().plusMillis(delay));
        log.info("event {} attempt {} failed ({}), retry in {} ms", event.getEventId(), attempt, error, delay);
        scheduler.schedule(() -> workers.submit(() -> attempt(event, attempt + 1)), delay, TimeUnit.MILLISECONDS);
    }

    long backoffMillis(int failedAttempt) {
        long base = properties.getCallback().getBackoffBaseMs();
        long max = properties.getCallback().getBackoffMaxMs();
        long delay = base * (1L << (failedAttempt - 1));
        return Math.min(delay, max);
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        scheduler.shutdownNow();
    }

    public record EventEnvelope(String eventId, String type, String customerId, long timestamp,
                                java.util.Map<String, Object> payload) {
        EventEnvelope(BusinessEvent event, long timestamp) {
            this(event.getEventId(), event.getType(), event.getCustomerId(), timestamp, event.getPayload());
        }
    }
}
