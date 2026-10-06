package com.github.lechandonga.openbankingconsentgatewayjava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("端到端集成：授权 -> 幂等支付 -> 配额限流 -> 签名回调")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EventDispatcher dispatcher;
    @Autowired private GatewayProperties gatewayProperties;
    @LocalServerPort private int port;

    @BeforeEach
    void pointReceiverAtLocalPort() {
        gatewayProperties.getCallback().setReceiverUrl(
                "http://127.0.0.1:" + port + "/callbacks/receiver");
    }

    private String createAuthorizedConsent(String clientId, String scopes) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", clientId, "scopes", new String[]{scopes}))))
                .andExpect(status().isCreated())
                .andReturn();
        String consentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("consentId").asText();
        mockMvc.perform(post("/api/consents/" + consentId + "/authorize"))
                .andExpect(status().isOk());
        return consentId;
    }

    private String paymentJson(String clientId, String consentId, String amount, String key) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "clientId", clientId,
                "consentId", consentId,
                "scopes", new String[]{"payments"},
                "paymentInformationId", key,
                "creditorAccount", "GB-ACC-1",
                "amount", amount,
                "currency", "GBP"));
    }

    @Test
    @DisplayName("支付全链路：重复幂等键返回一致结果；超额返回剩余额度与重置时间；回调签名通过且事件去重")
    void fullFlow() throws Exception {
        String consentId = createAuthorizedConsent("acme", "payments");

        // 首次支付
        String body1 = paymentJson("acme", consentId, "10.00", "pay-1");
        MvcResult first = mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String paymentId = firstJson.get("payment").get("paymentId").asText();
        int execNo = firstJson.get("payment").get("executionNo").asInt();

        // 相同幂等键重复提交：结果一致、标记 replay、副作用执行号不变
        MvcResult replay = mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();
        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertEquals(paymentId, replayJson.get("payment").get("paymentId").asText());
        assertEquals(execNo, replayJson.get("payment").get("executionNo").asInt(),
                "重复提交不得重复执行业务副作用");

        // 同键不同内容 -> 409
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("acme", consentId, "999.00", "pay-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // 未授权访问 -> 403 且原因可区分
        String otherConsent = createAuthorizedConsent("acme", "accounts");
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "idem-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("acme", otherConsent, "1.00", "pay-denied")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONSENT_SCOPE_MISMATCH"));

        // 用满剩余配额（测试配置 limit=5，已成功 1 笔 -> 再发 4 笔）
        for (int i = 2; i <= 5; i++) {
            mockMvc.perform(post("/api/payments")
                            .header("Idempotency-Key", "idem-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(paymentJson("acme", consentId, "10.00", "pay-" + i)))
                    .andExpect(status().isCreated());
        }
        // 第 6 笔超配额 -> 429，带剩余额度 0 与重置时间
        mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", "idem-over")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("acme", consentId, "10.00", "pay-over")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.details.remaining").value(0))
                .andExpect(jsonPath("$.details.resetAt").exists());

        // 等待异步回调投递完成；接收方使用真实 HTTP 传输打到内置验签端点
        dispatcher.awaitIdle(10000);

        // 事件可查询、状态已投递；每个支付只产生一个事件（事件去重）
        MvcResult events = mockMvc.perform(get("/api/events?status=DELIVERED"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode delivered = objectMapper.readTree(events.getResponse().getContentAsString());
        assertTrue(delivered.isArray());
        assertEquals(5, delivered.size(), "成功的 5 笔支付各产生 1 个事件，重复提交不新增事件");

        // 接收方记录显示每个事件只被接受一次（投递成功无重复）
        MvcResult received = mockMvc.perform(get("/callbacks/receiver/received"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode counts = objectMapper.readTree(received.getResponse().getContentAsString());
        counts.fields().forEachRemaining(e -> assertEquals(1, e.getValue().asInt(),
                "事件 " + e.getKey() + " 不应被重复接受"));
    }
}
