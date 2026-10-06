package com.github.lechandonga.openbankingconsentgatewayjava.tests;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.github.lechandonga.openbankingconsentgatewayjava.event.CallbackTransport;

/**
 * 端到端：接收方始终返回 500，事件在真实 HTTP 链路上走完重试并进入死信。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.callback.max-attempts=3",
                "gateway.callback.backoff-base-ms=40",
                "gateway.callback.signing-secret=it-demo-secret"
        })
class HttpDeadLetterTest {

    private static final String BIZ = "端到端HTTP-死信";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @TestConfiguration
    static class FailingTransportConfig {
        @org.springframework.beans.factory.annotation.Autowired
        org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext context;

        @Bean
        @Primary
        CallbackTransport failingTransport() {
            return (event, body, timestamp, signature) -> {
                HttpHeaders headers = new HttpHeaders();
                headers.add("X-Event-Id", event.getEventId());
                headers.add("X-Timestamp", Long.toString(timestamp));
                headers.add("X-Signature", signature);
                ResponseEntity<String> response = new TestRestTemplate().exchange(
                        "http://localhost:" + context.getWebServer().getPort() + "/internal/receiver/fail",
                        HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                throw new IllegalStateException("receiver responded HTTP " + response.getStatusCode());
            };
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesEndInQueryableDeadLetter() {
        TestLog.business(BIZ, "真实 HTTP 重试耗尽后死信可查询");
        TestLog.caseNo(BIZ, 1, "事件投递 /internal/receiver/fail 共 3 次，随后 DEAD 且可经 API 查询失败原因");

        Map<String, Object> eventReq = Map.of(
                "eventId", "evt-http-dead-1",
                "type", "payment.failed",
                "customerId", "cust-dead",
                "payload", Map.of("paymentId", "pay-9"));
        rest.postForEntity("http://localhost:" + port + "/api/events", eventReq, Map.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<Map> resp = rest.getForEntity(
                    "http://localhost:" + port + "/api/events/evt-http-dead-1", Map.class);
            assertEquals("DEAD", resp.getBody().get("status"));
        });

        ResponseEntity<Map> eventResp = rest.getForEntity(
                "http://localhost:" + port + "/api/events/evt-http-dead-1", Map.class);
        assertEquals(3, eventResp.getBody().get("attempts"));
        assertTrue(((String) eventResp.getBody().get("lastError")).contains("HTTP 500"));

        ResponseEntity<List> deadLetters = rest.getForEntity(
                "http://localhost:" + port + "/api/events/dead-letters", List.class);
        assertTrue(deadLetters.getBody().stream()
                .anyMatch(item -> "evt-http-dead-1".equals(((Map<String, Object>) item).get("eventId"))));
        TestLog.pass("attempts=3, lastError 含 HTTP 500，死信列表包含该事件");
    }
}
