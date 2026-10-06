package com.github.lechandonga.openbankingconsentgatewayjava.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.lechandonga.openbankingconsentgatewayjava.common.ApiException;
import com.github.lechandonga.openbankingconsentgatewayjava.gateway.GatewayService;
import com.github.lechandonga.openbankingconsentgatewayjava.gateway.IdempotencyConflictException;
import com.github.lechandonga.openbankingconsentgatewayjava.gateway.QuotaExceededException;

@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final GatewayService gatewayService;

    /** 演示用业务副作用计数器：每次真实业务执行 +1，幂等回放不增加。 */
    private final AtomicInteger sideEffectCounter = new AtomicInteger();

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/customers/{customerId}/requests")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String customerId,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody String payload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header is required");
        }
        GatewayService.Submission result = gatewayService.submit(customerId, idempotencyKey, payload, () -> {
            int seq = sideEffectCounter.incrementAndGet();
            Map<String, Object> businessResult = new LinkedHashMap<>();
            businessResult.put("accepted", true);
            businessResult.put("executionNo", seq);
            businessResult.put("echo", payload);
            return businessResult;
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("replay", result.replay());
        body.put("result", result.body());
        HttpStatus status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/customers/{customerId}/quota")
    public Map<String, Object> quota(@PathVariable String customerId) {
        return gatewayService.quotaStatus(customerId);
    }

    public int sideEffectCount() {
        return sideEffectCounter.get();
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotencyConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "IDEMPOTENCY_KEY_CONFLICT");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuota(QuotaExceededException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "QUOTA_EXCEEDED");
        body.put("message", ex.getMessage());
        body.put("limit", ex.getLimit());
        body.put("remaining", ex.getRemaining());
        body.put("resetAt", ex.getResetAtEpochMs());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }
}
