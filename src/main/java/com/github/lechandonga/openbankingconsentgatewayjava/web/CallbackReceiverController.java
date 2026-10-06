package com.github.lechandonga.openbankingconsentgatewayjava.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lechandonga.openbankingconsentgatewayjava.event.SignatureUtil;

/**
 * 本地内置的回调接收方（模拟业务系统）：
 * - 校验 X-Signature，拒绝伪造请求；
 * - 用 X-Event-Id 识别重复投递；
 * - 用 X-Timestamp 偏移识别重放；
 * - /internal/receiver/fail 始终返回 500，便于演示退避重试与死信。
 */
@RestController
@RequestMapping("/internal")
public class CallbackReceiverController {

    private static final Logger log = LoggerFactory.getLogger(CallbackReceiverController.class);

    private final GatewayProperties properties;
    private final Set<String> acceptedEvents = ConcurrentHashMap.newKeySet();

    public CallbackReceiverController(GatewayProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/receiver")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String body,
                                                       @RequestHeader("X-Event-Id") String eventId,
                                                       @RequestHeader("X-Timestamp") long timestamp,
                                                       @RequestHeader("X-Signature") String signature) {
        return handle(body, eventId, timestamp, signature, false);
    }

    @PostMapping("/receiver/fail")
    public ResponseEntity<Map<String, Object>> receiveAlwaysFails(@RequestBody String body,
                                                                  @RequestHeader("X-Event-Id") String eventId,
                                                                  @RequestHeader("X-Timestamp") long timestamp,
                                                                  @RequestHeader("X-Signature") String signature) {
        return handle(body, eventId, timestamp, signature, true);
    }

    private ResponseEntity<Map<String, Object>> handle(String body, String eventId, long timestamp,
                                                       String signature, boolean failing) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("receivedAt", Instant.now().toString());

        if (!SignatureUtil.verify(properties.getCallback().getSigningSecret(), eventId, timestamp, body, signature)) {
            log.warn("receiver rejected event {}: invalid signature", eventId);
            result.put("accepted", false);
            result.put("reason", "INVALID_SIGNATURE");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
        }

        long skewSeconds = Math.abs(System.currentTimeMillis() - timestamp) / 1000;
        if (skewSeconds > properties.getCallback().getMaxTimestampSkewSeconds()) {
            log.warn("receiver rejected event {}: stale timestamp (skew={}s)", eventId, skewSeconds);
            result.put("accepted", false);
            result.put("reason", "REPLAY_SUSPECTED");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        }

        boolean duplicate = !acceptedEvents.add(eventId);
        result.put("duplicate", duplicate);

        if (failing) {
            result.put("accepted", false);
            result.put("reason", "SIMULATED_FAILURE");
            log.info("receiver simulated failure for event {} (duplicate={})", eventId, duplicate);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }

        result.put("accepted", true);
        log.info("receiver accepted event {} (duplicate={})", eventId, duplicate);
        return ResponseEntity.ok(result);
    }

    public Set<String> getAcceptedEvents() {
        return Set.copyOf(acceptedEvents);
    }
}
