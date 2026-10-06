package com.github.lechandonga.openbankingconsentgatewayjava.event;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("回调投递业务：指数退避重试、死信与不重复入队")
class EventDispatcherTest {

    private GatewayProperties props;
    private EventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        props.getCallback().setMaxAttempts(4);
        props.getCallback().setBackoffBaseMillis(10);
    }

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.stop();
        }
    }

    private Event newEvent(String id) {
        return new Event(id, "test.type", "subj-" + id, "{\"k\":\"v\"}", Instant.now());
    }

    @Test
    @DisplayName("接收方持续失败：按退避重试到上限后进入可查询的死信并保留失败原因")
    void retryThenDeadLetter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        dispatcher = new EventDispatcher(props, (url, event, body, sig, nonce, ts) -> {
            calls.incrementAndGet();
            return false;
        });
        dispatcher.start();

        Event event = newEvent("evt-dead");
        dispatcher.enqueue(event);
        dispatcher.awaitIdle(5000);

        assertEquals(4, calls.get(), "最大尝试次数（含首次）应为 4");
        assertEquals(EventStatus.DEAD, event.getStatus());
        assertEquals(4, event.getAttempts());
        assertNotNull(event.getLastError());
        assertFalse(event.getLastError().isBlank(), "死信必须保留失败原因");
    }

    @Test
    @DisplayName("首次失败后重试成功：事件最终 DELIVERED，尝试次数大于 1")
    void retryThenSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        dispatcher = new EventDispatcher(props, (url, event, body, sig, nonce, ts) ->
                calls.incrementAndGet() >= 3);
        dispatcher.start();

        Event event = newEvent("evt-ok");
        dispatcher.enqueue(event);
        dispatcher.awaitIdle(5000);

        assertEquals(EventStatus.DELIVERED, event.getStatus());
        assertEquals(3, event.getAttempts());
    }

    @Test
    @DisplayName("同一事件重复 enqueue 不会产生重复投递任务（只投递一次）")
    void duplicateEnqueueNotDeliveredTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        dispatcher = new EventDispatcher(props, (url, event, body, sig, nonce, ts) -> {
            calls.incrementAndGet();
            return true;
        });
        dispatcher.start();

        Event event = newEvent("evt-once");
        dispatcher.enqueue(event);
        dispatcher.enqueue(event);
        dispatcher.enqueue(event);
        dispatcher.awaitIdle(5000);

        assertEquals(1, calls.get(), "同一事件不得因重复入队而多次投递");
        assertEquals(EventStatus.DELIVERED, event.getStatus());
    }

    @Test
    @DisplayName("EventService.publishIfAbsent 对同一事件只入队一次")
    void serviceDeduplicatesPublish() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        dispatcher = new EventDispatcher(props, (url, event, body, sig, nonce, ts) -> {
            calls.incrementAndGet();
            return true;
        });
        dispatcher.start();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        EventService service = new EventService(mapper, dispatcher);

        service.publishIfAbsent("evt-x", "t", "s", java.util.Map.of("v", 1));
        service.publishIfAbsent("evt-x", "t", "s", java.util.Map.of("v", 2));
        Event again = service.publishIfAbsent("evt-x", "t", "s", java.util.Map.of("v", 3));
        dispatcher.awaitIdle(5000);

        assertEquals(1, service.all().size());
        assertEquals(1, calls.get());
        assertEquals(EventStatus.DELIVERED, again.getStatus());
    }
}
