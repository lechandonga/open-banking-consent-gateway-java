package com.github.lechandonga.openbankingconsentgatewayjava.event;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 回调签名：HMAC-SHA256(secret, eventId + "\n" + timestamp + "\n" + body)，十六进制小写。
 * 接收方用相同算法比对 X-Signature，并用 X-Event-Id 去重、X-Timestamp 防重放。
 */
public final class SignatureUtil {

    private SignatureUtil() {
    }

    public static String sign(String secret, String eventId, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = eventId + "\n" + timestamp + "\n" + body;
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute signature", e);
        }
    }

    public static boolean verify(String secret, String eventId, long timestamp, String body, String signature) {
        if (signature == null) {
            return false;
        }
        String expected = sign(secret, eventId, timestamp, body);
        return constantTimeEquals(expected, signature);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}
