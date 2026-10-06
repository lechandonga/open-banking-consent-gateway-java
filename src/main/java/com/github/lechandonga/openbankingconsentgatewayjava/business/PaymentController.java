package com.github.lechandonga.openbankingconsentgatewayjava.business;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openbankingconsentgatewayjava.business.dto.PaymentRequest;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.ConsentService;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventService;
import com.github.lechandonga.openbankingconsentgatewayjava.ratelimit.IdempotencyService;
import com.github.lechandonga.openbankingconsentgatewayjava.ratelimit.QuotaService;
import com.github.lechandonga.openbankingconsentgatewayjava.ratelimit.QuotaTicket;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 开放银行支付网关：授权校验 -> 幂等去重 -> 配额占用 -> 业务执行（仅一次）-> 发布业务事件。
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final ConsentService consentService;
    private final QuotaService quotaService;
    private final IdempotencyService idempotencyService;
    private final PaymentExecutionService paymentExecutionService;
    private final EventService eventService;
    private final ObjectMapper objectMapper;

    public PaymentController(ConsentService consentService, QuotaService quotaService,
                             IdempotencyService idempotencyService,
                             PaymentExecutionService paymentExecutionService,
                             EventService eventService, ObjectMapper objectMapper) {
        this.consentService = consentService;
        this.quotaService = quotaService;
        this.idempotencyService = idempotencyService;
        this.paymentExecutionService = paymentExecutionService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<byte[]> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentRequest request) throws JsonProcessingException {
        if (request.getClientId() == null || request.getClientId().isBlank()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        Set<String> scopes = request.getScopes() == null ? Set.of("payments") : request.getScopes();

        IdempotencyService.ExecutedResponse result = idempotencyService.execute(
                request.getClientId(), idempotencyKey, request, () -> {
                    // 幂等首次请求：先校验授权，再占用配额，最后执行业务副作用（顺序执行，失败不占副作用）
                    consentService.checkAccess(request.getConsentId(), request.getClientId(), scopes);
                    QuotaTicket ticket = quotaService.acquire(request.getClientId());
                    Map<String, Object> payment = paymentExecutionService.submit(request);
                    // 业务成功后发布事件（异步回调），事件入队本身去重
                    eventService.publishIfAbsent("payment.completed",
                            String.valueOf(payment.get("paymentId")), payment);
                    Map<String, Object> body = Map.of(
                            "payment", payment,
                            "quota", Map.of(
                                    "remaining", ticket.remaining(),
                                    "limit", ticket.limit(),
                                    "resetAt", ticket.resetAt().toString()));
                    return new IdempotencyService.ExecutedResponse(
                            HttpStatus.CREATED.value(), objectMapper.writeValueAsString(body), false);
                });

        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replay", String.valueOf(result.replay()))
                .body(result.bodyBytes());
    }

    @GetMapping("/quota/{clientId}")
    public Map<String, Object> quota(@PathVariable String clientId) {
        QuotaTicket t = quotaService.peek(clientId);
        return Map.of("clientId", t.clientId(), "remaining", t.remaining(),
                "limit", t.limit(), "resetAt", t.resetAt().toString());
    }
}
