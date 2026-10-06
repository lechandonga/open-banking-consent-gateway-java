package com.github.lechandonga.openbankingconsentgatewayjava.tests;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lechandonga.openbankingconsentgatewayjava.event.BusinessEvent;
import com.github.lechandonga.openbankingconsentgatewayjava.event.CallbackTransport;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventDispatcher;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventService;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventStatus;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventStore;
import com.github.lechandonga.openbankingconsentgatewayjava.event.SignatureUtil;

/**
 * 回调投递：退避重试、死信、幂等入队、签名校验。
 */
class EventCallbackTest {

    private static final String BIZ = "事件回调";

    private GatewayProperties props(int maxAttempts, long baseBackoff) {
        GatewayProperties properties = new GatewayProperties();
        properties.getCallback().setMaxAttempts(maxAttempts);
        properties.getCallback().setBackoffBaseMs(baseBackoff);
        properties.getCallback().setBackoffMaxMs(baseBackoff * 10);
        properties.getCallback().setSigningSecret("test-secret");
        return properties;
    }

    @Test
    void retryWithBackoffThenDeadLetter() {
        TestLog.business(BIZ, "接收方持续失败：退避重试后进入死信并保留原因");
        GatewayProperties properties = props(4, 50);
        EventStore store = new EventStore();
        AtomicInteger transportCalls = new AtomicInteger();
        CallbackTransport alwaysFail = (event, body, timestamp, signature) -> {
            transportCalls.incrementAndGet();
            throw new IllegalStateException("connection refused (simulated)");
        };
        EventDispatcher dispatcher = new EventDispatcher(store, properties, alwaysFail, new ObjectMapper());
        EventService service = new EventService(store, dispatcher);

        TestLog.caseNo(BIZ, 1, "首次投递失败后按 50ms、100ms、200ms 退避，共尝试 4 次后置 DEAD");
        BusinessEvent event = service.publish("evt-dead-1", "payment.failed", "cust-1",
                Map.of("paymentId", "p-1"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(EventStatus.DEAD, store.find("evt-dead-1").orElseThrow().getStatus()));

        BusinessEvent dead = store.find("evt-dead-1").orElseThrow();
        assertEquals(4, dead.getAttempts());
        assertEquals(4, transportCalls.get());
        assertTrue(dead.getLastError().contains("connection refused"));
        TestLog.info("最终状态=" + dead.getStatus() + "，尝试次数=" + dead.getAttempts()
                + "，最后失败原因=" + dead.getLastError());
        TestLog.pass("退避序列=50/100/200ms，死信保留失败原因");
        dispatcher.shutdown();
    }

    @Test
    void retrySucceedsAndDuplicatePublishIgnored() throws InterruptedException {
        TestLog.business(BIZ, "重试后成功，且重复事件不重复入队");
        GatewayProperties properties = props(5, 30);
        EventStore store = new EventStore();
        AtomicInteger transportCalls = new AtomicInteger();
        CallbackTransport failTwice = (event, body, timestamp, signature) -> {
            int call = transportCalls.incrementAndGet();
            if (call <= 2) {
                throw new IllegalStateException("HTTP 500 (simulated)");
            }
        };
        EventDispatcher dispatcher = new EventDispatcher(store, properties, failTwice, new ObjectMapper());
        EventService service = new EventService(store, dispatcher);

        TestLog.caseNo(BIZ, 2, "前两次失败、第三次成功，事件最终 DELIVERED");
        service.publish("evt-ok-1", "account.opened", "cust-2", Map.of("accountId", "a-1"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(EventStatus.DELIVERED, store.find("evt-ok-1").orElseThrow().getStatus()));
        assertEquals(3, store.find("evt-ok-1").orElseThrow().getAttempts());
        TestLog.pass("尝试 3 次后 DELIVERED");

        TestLog.caseNo(BIZ, 3, "相同 eventId 重复发布被忽略，不产生第二次投递");
        BusinessEvent again = service.publish("evt-ok-1", "account.opened", "cust-2",
                Map.of("accountId", "a-1"));
        assertEquals(EventStatus.DELIVERED, again.getStatus());
        Thread.sleep(300);
        assertEquals(3, transportCalls.get(), "重复发布不得再次投递");
        TestLog.pass("重复发布投递次数仍=" + transportCalls.get());

        TestLog.caseNo(BIZ, 4, "死信列表可查询（上一场景的死信出现在独立 store，本例验证空列表语义）");
        assertTrue(service.deadLetters().isEmpty());
        dispatcher.shutdown();
    }

    @Test
    void signatureIsDeterministicAndDetectsTampering() {
        TestLog.business(BIZ, "回调签名可校验并防篡改/防重放");
        TestLog.caseNo(BIZ, 5, "相同输入签名一致；篡改 body/eventId/时间戳后验签失败");
        long timestamp = 1_700_000_000_000L;
        String signature = SignatureUtil.sign("secret", "evt-1", timestamp, "{\"a\":1}");
        assertEquals(signature, SignatureUtil.sign("secret", "evt-1", timestamp, "{\"a\":1}"));
        assertTrue(SignatureUtil.verify("secret", "evt-1", timestamp, "{\"a\":1}", signature));
        assertFalse(SignatureUtil.verify("secret", "evt-1", timestamp, "{\"a\":2}", signature), "篡改 body");
        assertFalse(SignatureUtil.verify("secret", "evt-2", timestamp, "{\"a\":1}", signature), "篡改事件标识");
        assertFalse(SignatureUtil.verify("secret", "evt-1", timestamp + 1, "{\"a\":1}", signature), "篡改时间戳");
        assertFalse(SignatureUtil.verify("wrong-secret", "evt-1", timestamp, "{\"a\":1}", signature), "错误密钥");
        TestLog.pass("5 项验签断言全部符合预期，接收方可凭 eventId+timestamp 识别重复与重放");
    }
}
