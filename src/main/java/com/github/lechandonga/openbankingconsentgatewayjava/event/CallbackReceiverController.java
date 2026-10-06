package com.github.lechandonga.openbankingconsentgatewayjava.event;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置回调接收方（演示接收方应如何校验）：
 * 1) 用 X-Gateway-Timestamp 拒绝过期投递（防重放）；
 * 2) 用 X-Gateway-Nonce 去重（同一 nonce 只接受一次）；
 * 3) 重算 X-Gateway-Signature（HMAC-SHA256）校验签名与载荷完整性；
 * 4) 相同 X-Gateway-Event-Id 的重复投递可识别并幂等处理。
 *
 * 可用 X-Simulate-Status 头让接收方返回指定状态码，便于本地验证重试与死信。
 */
@RestController
@RequestMapping("/callbacks")
public class CallbackReceiverController {

    private static final Logger log = LoggerFactory.getLogger(CallbackReceiverController.class);

    private final GatewayProperties properties;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    private final Map<String, Integer> receivedEventCounts = new ConcurrentHashMap<>();

    public CallbackReceiverController(GatewayProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/receiver")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader("X-Gateway-Event-Id") String eventId,
            @RequestHeader("X-Gateway-Nonce") String nonce,
            @RequestHeader("X-Gateway-Timestamp") long timestamp,
            @RequestHeader("X-Gateway-Signature") String signature,
            @RequestHeader(value = "X-Simulate-Status", required = false) Integer simulateStatus,
            @RequestBody String body) {

        if (simulateStatus != null && simulateStatus >= 400) {
            log.warn("接收方模拟失败 eventId={} status={}", eventId, simulateStatus);
            return ResponseEntity.status(simulateStatus)
                    .body(Map.of("accepted", false, "reason", "simulated"));
        }

        long toleranceMs = properties.getCallback().getTimestampToleranceSeconds() * 1000;
        if (Math.abs(System.currentTimeMillis() - timestamp) > toleranceMs) {
            log.warn("回调时间戳过期 eventId={}", eventId);
            return ResponseEntity.status(401).body(Map.of("accepted", false, "reason", "timestamp_expired"));
        }

        String expectedBase = SignatureUtil.signingBase(eventId, nonce, timestamp, body);
        if (!SignatureUtil.verify(expectedBase, properties.getCallback().getSigningSecret(), signature)) {
            log.warn("回调签名校验失败 eventId={}", eventId);
            return ResponseEntity.status(401).body(Map.of("accepted", false, "reason", "bad_signature"));
        }

        if (seenNonces.putIfAbsent(nonce, timestamp) != null) {
            log.warn("回调 nonce 重复，判定为重放 eventId={} nonce={}", eventId, nonce);
            return ResponseEntity.status(409).body(Map.of("accepted", false, "reason", "nonce_replay"));
        }

        int count = receivedEventCounts.merge(eventId, 1, Integer::sum);
        log.info("接收方接受回调 eventId={} duplicate={} 历史投递次数={}", eventId, count > 1, count);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accepted", true);
        resp.put("eventId", eventId);
        resp.put("duplicate", count > 1);
        resp.put("deliveryCount", count);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/receiver/received")
    public Map<String, Integer> received() {
        return Map.copyOf(receivedEventCounts);
    }
}
