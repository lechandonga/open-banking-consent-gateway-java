package com.github.lechandonga.openbankingconsentgatewayjava.tests;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.github.lechandonga.openbankingconsentgatewayjava.event.CallbackTransport;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventStatus;

/**
 * 端到端 HTTP 测试：随机端口启动完整应用，不依赖任何外部服务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.quota.limit=3",
                "gateway.callback.max-attempts=4",
                "gateway.callback.backoff-base-ms=60",
                "gateway.callback.signing-secret=it-demo-secret"
        })
class HttpEndToEndTest {

    private static final String BIZ = "端到端HTTP";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @TestConfiguration
    static class LocalTransportConfig {
        @org.springframework.beans.factory.annotation.Autowired
        org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext context;

        @Bean
        @Primary
        CallbackTransport localTransport() {
            return (event, body, timestamp, signature) -> {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.add("X-Event-Id", event.getEventId());
                headers.add("X-Timestamp", Long.toString(timestamp));
                headers.add("X-Signature", signature);
                HttpEntity<String> request = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = new TestRestTemplate().exchange(
                        "http://localhost:" + context.getWebServer().getPort() + "/internal/receiver",
                        HttpMethod.POST, request, String.class);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException("receiver HTTP " + response.getStatusCode());
                }
            };
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullFlow() {
        TestLog.business(BIZ, "REST 接口端到端验证");

        TestLog.caseNo(BIZ, 1, "创建授权 -> 授权 -> 合法访问 200");
        Map<String, Object> createReq = Map.of(
                "customerId", "cust-http",
                "scopes", Set.of("accounts.read"),
                "ttlSeconds", 3600);
        ResponseEntity<Map> created = rest.postForEntity(url("/api/consents"), createReq, Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String consentId = (String) created.getBody().get("id");
        TestLog.info("创建授权 id=" + consentId + ", status=" + created.getBody().get("status"));

        rest.postForEntity(url("/api/consents/" + consentId + "/authorize"), null, Map.class);
        ResponseEntity<Map> allowed = rest.postForEntity(
                url("/api/consents/" + consentId + "/access?scope=accounts.read"), null, Map.class);
        assertEquals(HttpStatus.OK, allowed.getStatusCode());
        assertEquals(true, allowed.getBody().get("allowed"));
        TestLog.pass("合法访问放行");

        TestLog.caseNo(BIZ, 2, "四类拒绝原因通过 HTTP 状态码与 code 字段可区分");
        ResponseEntity<Map> scopeDenied = rest.postForEntity(
                url("/api/consents/" + consentId + "/access?scope=cards.read"), null, Map.class);
        assertEquals(HttpStatus.FORBIDDEN, scopeDenied.getStatusCode());
        assertEquals("SCOPE_MISMATCH", scopeDenied.getBody().get("code"));

        rest.postForEntity(url("/api/consents/" + consentId + "/revoke"), null, Map.class);
        ResponseEntity<Map> revokedDenied = rest.postForEntity(
                url("/api/consents/" + consentId + "/access?scope=accounts.read"), null, Map.class);
        assertEquals("REVOKED", revokedDenied.getBody().get("code"));

        Map<String, Object> expiredReq = Map.of(
                "customerId", "cust-http",
                "scopes", Set.of("accounts.read"),
                "ttlSeconds", 1);
        ResponseEntity<Map> expiredCreated = rest.postForEntity(url("/api/consents"), expiredReq, Map.class);
        String expiredId = (String) expiredCreated.getBody().get("id");
        rest.postForEntity(url("/api/consents/" + expiredId + "/authorize"), null, Map.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ResponseEntity<Map> resp = rest.postForEntity(
                    url("/api/consents/" + expiredId + "/access?scope=accounts.read"), null, Map.class);
            assertEquals("EXPIRED", resp.getBody().get("code"));
        });

        ResponseEntity<Map> unauthorized = rest.postForEntity(
                url("/api/consents/does-not-exist/access?scope=accounts.read"), null, Map.class);
        assertEquals("UNAUTHORIZED", unauthorized.getBody().get("code"));
        TestLog.pass("UNAUTHORIZED / REVOKED / EXPIRED / SCOPE_MISMATCH 均可区分");

        TestLog.caseNo(BIZ, 3, "幂等：同键同内容回放（200），同键异内容 409");
        HttpHeaders headers = new HttpHeaders();
        headers.add("Idempotency-Key", "idem-http-1");
        HttpEntity<String> first = new HttpEntity<>("{\"n\":1}", headers);
        ResponseEntity<Map> firstResp = rest.exchange(
                url("/api/gateway/customers/cust-http/requests"), HttpMethod.POST, first, Map.class);
        assertEquals(HttpStatus.CREATED, firstResp.getStatusCode());
        assertEquals(false, firstResp.getBody().get("replay"));

        ResponseEntity<Map> replayResp = rest.exchange(
                url("/api/gateway/customers/cust-http/requests"), HttpMethod.POST, first, Map.class);
        assertEquals(true, replayResp.getBody().get("replay"));
        Map firstResult = (Map) firstResp.getBody().get("result");
        Map replayResult = (Map) replayResp.getBody().get("result");
        assertEquals(firstResult.get("executionNo"), replayResult.get("executionNo"), "回放结果必须与首次一致");

        HttpEntity<String> conflict = new HttpEntity<>("{\"n\":2}", headers);
        ResponseEntity<Map> conflictResp = rest.exchange(
                url("/api/gateway/customers/cust-http/requests"), HttpMethod.POST, conflict, Map.class);
        assertEquals(HttpStatus.CONFLICT, conflictResp.getStatusCode());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflictResp.getBody().get("code"));
        TestLog.pass("回放命中且结果一致，冲突请求 409");

        TestLog.caseNo(BIZ, 4, "配额=3：第 4 个新请求 429，响应带 remaining=0 与 resetAt");
        for (int i = 2; i <= 3; i++) {
            HttpHeaders h = new HttpHeaders();
            h.add("Idempotency-Key", "idem-http-" + i);
            ResponseEntity<Map> ok = rest.exchange(url("/api/gateway/customers/cust-http/requests"),
                    HttpMethod.POST, new HttpEntity<>("{\"n\":" + i + "}", h), Map.class);
            assertEquals(HttpStatus.CREATED, ok.getStatusCode());
        }
        HttpHeaders overflowHeader = new HttpHeaders();
        overflowHeader.add("Idempotency-Key", "idem-http-4");
        ResponseEntity<Map> overflow = rest.exchange(url("/api/gateway/customers/cust-http/requests"),
                HttpMethod.POST, new HttpEntity<>("{\"n\":4}", overflowHeader), Map.class);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, overflow.getStatusCode());
        assertEquals("QUOTA_EXCEEDED", overflow.getBody().get("code"));
        assertEquals(0, overflow.getBody().get("remaining"));
        assertTrue(((Number) overflow.getBody().get("resetAt")).longValue() > System.currentTimeMillis());
        TestLog.pass("429 响应 remaining=0, resetAt=" + overflow.getBody().get("resetAt"));

        TestLog.caseNo(BIZ, 5, "发布事件后异步投递到内置接收方并验签成功，事件 DELIVERED");
        Map<String, Object> eventReq = Map.of(
                "eventId", "evt-http-ok",
                "type", "payment.completed",
                "customerId", "cust-http",
                "payload", Map.of("paymentId", "pay-1", "amount", 100));
        ResponseEntity<Map> published = rest.postForEntity(url("/api/events"), eventReq, Map.class);
        assertEquals(HttpStatus.ACCEPTED, published.getStatusCode());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<Map> resp = rest.getForEntity(url("/api/events/evt-http-ok"), Map.class);
            assertEquals(EventStatus.DELIVERED.name(), resp.getBody().get("status"));
        });
        TestLog.pass("事件 evt-http-ok 经签名校验后被接收方确认");
    }

}
