package com.github.lechandonga.openbankingconsentgatewayjava.ratelimit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户 + 幂等键维度的请求幂等控制。
 *
 * 规则：
 * 1) 同一幂等键的首次请求执行业务，结果被缓存；重复提交直接返回首次结果，业务不再执行。
 * 2) 同一幂等键但请求体指纹不同 -> 明确拒绝（409，IDEMPOTENCY_KEY_REUSED）。
 * 3) 同键并发提交：在键级别锁内串行化，只有一个请求成为"首个"，其它等待并复用结果，
 *    业务副作用最多执行一次。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private record CachedResponse(String requestHash, int status, String body) {}

    private final Map<String, CachedResponse> store = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public IdempotencyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static String key(String clientId, String idempotencyKey) {
        return clientId + "|" + idempotencyKey;
    }

    public String fingerprint(Object requestBody) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(requestBody);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算请求指纹", e);
        }
    }

    /**
     * 在幂等保护下执行业务。返回 ExecutedResponse（含是否命中缓存标记）。
     */
    public ExecutedResponse execute(String clientId, String idempotencyKey, Object requestBody,
                                    Callable<ExecutedResponse> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 不能为空");
        }
        String fullKey = key(clientId, idempotencyKey);
        String requestHash = fingerprint(requestBody);
        synchronized (locks.computeIfAbsent(fullKey, k -> new Object())) {
            CachedResponse cached = store.get(fullKey);
            if (cached != null) {
                if (!cached.requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            "幂等键 " + idempotencyKey + " 已用于不同的请求内容");
                }
                log.info("幂等命中 clientId={} key={}，直接返回首次结果，业务不重复执行",
                        clientId, idempotencyKey);
                return new ExecutedResponse(cached.status(), cached.body(), true);
            }
            try {
                ExecutedResponse result = action.call();
                store.put(fullKey, new CachedResponse(requestHash, result.status(), result.body()));
                log.info("幂等首次执行 clientId={} key={} status={}", clientId, idempotencyKey, result.status());
                return new ExecutedResponse(result.status(), result.body(), false);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Optional<ExecutedResponse> findReplay(String clientId, String idempotencyKey, Object requestBody) {
        String fullKey = key(clientId, idempotencyKey);
        CachedResponse cached = store.get(fullKey);
        if (cached == null) {
            return Optional.empty();
        }
        if (!cached.requestHash().equals(fingerprint(requestBody))) {
            throw new IdempotencyConflictException(
                    "幂等键 " + idempotencyKey + " 已用于不同的请求内容");
        }
        return Optional.of(new ExecutedResponse(cached.status(), cached.body(), true));
    }

    public record ExecutedResponse(int status, String body, boolean replay) {
        public byte[] bodyBytes() { return body.getBytes(StandardCharsets.UTF_8); }
    }
}
