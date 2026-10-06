package com.github.lechandonga.openbankingconsentgatewayjava.event;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步回调投递调度器：单线程串行调度，指数退避重试，超过上限进入死信。
 *
 * 退避：第 1 次失败后延迟 base*2^0，第 2 次 base*2^1 ...（上限受 maxAttempts 控制）。
 * 同一事件在调度层同时只会存在一个待办（active 集合去重），不会重复入队/并发投递。
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final GatewayProperties properties;
    private final CallbackTransport transport;
    private ScheduledExecutorService scheduler;
    private final Map<String, Event> active = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    public EventDispatcher(GatewayProperties properties, CallbackTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-callback-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 入队：同一事件重复调用不会产生多个投递任务。 */
    public void enqueue(Event event) {
        if (active.putIfAbsent(event.getEventId(), event) == null) {
            scheduler.execute(() -> deliver(event));
        }
    }

    private void deliver(Event event) {
        inFlight.incrementAndGet();
        try {
            int attempt = event.getAttempts() + 1;
            event.setAttempts(attempt);
            String nonce = UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            String base = SignatureUtil.signingBase(event.getEventId(), nonce, timestamp, event.getPayloadJson());
            String signature = SignatureUtil.sign(base, properties.getCallback().getSigningSecret());
            String url = properties.getCallback().getReceiverUrl();

            log.info("回调投递 eventId={} attempt={}/{} url={}",
                    event.getEventId(), attempt, properties.getCallback().getMaxAttempts(), url);
            boolean ok;
            try {
                ok = transport.deliver(url, event, event.getPayloadJson(), signature, nonce, timestamp);
            } catch (Exception e) {
                ok = false;
                event.setLastError("投递异常: " + e);
            }

            if (ok) {
                event.setLastError(null);
                event.setStatus(EventStatus.DELIVERED);
                active.remove(event.getEventId());
                log.info("回调投递成功 eventId={} attempts={}", event.getEventId(), attempt);
                return;
            }
            if (event.getLastError() == null) {
                event.setLastError("接收方返回非成功状态或网络失败 (attempt " + attempt + ")");
            }
            int max = properties.getCallback().getMaxAttempts();
            if (attempt >= max) {
                event.setStatus(EventStatus.DEAD);
                active.remove(event.getEventId());
                log.error("回调进入死信 eventId={} attempts={} lastError={}",
                        event.getEventId(), attempt, event.getLastError());
                return;
            }
            long delay = properties.getCallback().getBackoffBaseMillis() * (1L << (attempt - 1));
            log.info("回调调度重试 eventId={} nextAttempt={} delayMs={}",
                    event.getEventId(), attempt + 1, delay);
            scheduler.schedule(() -> deliver(event), delay, TimeUnit.MILLISECONDS);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 等待调度器队列与在途任务全部清空（供测试断言使用）。 */
    public void awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (active.isEmpty() && inFlight.get() == 0
                    && scheduler != null && !scheduler.isShutdown()) {
                // 再给一个极小窗口确认没有新调度
                Thread.sleep(10);
                if (active.isEmpty() && inFlight.get() == 0) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("等待事件调度器空闲超时，仍在处理: " + active.keySet());
    }
}
