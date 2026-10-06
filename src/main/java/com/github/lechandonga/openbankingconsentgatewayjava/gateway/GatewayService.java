package com.github.lechandonga.openbankingconsentgatewayjava.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

/**
 * 幂等 + 配额的组合判定。
 *
 * 判定顺序（在幂等键锁内）：
 * 1. 键不存在 -> 先占配额，再执行业务并缓存结果；
 * 2. 键存在且请求摘要一致 -> 回放首次结果，不扣配额、不重复副作用；
 * 3. 键存在但摘要不同 -> 409 拒绝。
 */
@Service
public class GatewayService {

    private final QuotaService quotaService;
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    public GatewayService(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    public static String compositeKey(String customerId, String idempotencyKey) {
        return customerId + ":" + idempotencyKey;
    }

    public static String hashPayload(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return 二选一：replay 命中已有结果，或 generated 为首次执行结果
     */
    public Submission submit(String customerId, String idempotencyKey, String payload, Supplier<Object> businessAction) {
        String composite = compositeKey(customerId, idempotencyKey);
        Object lock = keyLocks.computeIfAbsent(composite, key -> new Object());
        synchronized (lock) {
            String hash = hashPayload(payload);
            IdempotencyRecord existing = records.get(composite);
            if (existing != null) {
                if (!existing.getRequestHash().equals(hash)) {
                    throw new IdempotencyConflictException(idempotencyKey);
                }
                return new Submission(true, existing.getStatus(), existing.getResponse());
            }
            // 首次提交：先占配额（超额直接抛异常，不产生任何副作用）
            quotaService.acquire(customerId);
            Object result = businessAction.get();
            IdempotencyRecord record = new IdempotencyRecord(idempotencyKey, customerId, hash);
            record.complete(200, result);
            records.put(composite, record);
            return new Submission(false, 200, result);
        }
    }

    public Map<String, Object> quotaStatus(String customerId) {
        QuotaWindow window = quotaService.peek(customerId);
        int limit = quotaService.getLimit();
        int remaining = Math.max(0, limit - window.getUsed());
        long resetAt = window.getWindowStart() + quotaService.getWindowMs();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("limit", limit);
        body.put("used", window.getUsed());
        body.put("remaining", remaining);
        body.put("windowStart", window.getWindowStart());
        body.put("resetAt", resetAt);
        return body;
    }

    public record Submission(boolean replay, int status, Object body) {
    }
}
