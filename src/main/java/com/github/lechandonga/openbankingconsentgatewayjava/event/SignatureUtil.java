package com.github.lechandonga.openbankingconsentgatewayjava.event;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 回调签名：HMAC-SHA256(signingBase, secret)，signingBase = eventId . "\n" . nonce . "\n" . timestamp . "\n" . sha256Hex(body)。
 */
public final class SignatureUtil {

    private SignatureUtil() {}

    public static String signingBase(String eventId, String nonce, long timestamp, String body) {
        return eventId + "\n" + nonce + "\n" + timestamp + "\n" + sha256Hex(body);
    }

    public static String sign(String signingBase, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(signingBase.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    public static boolean verify(String signingBase, String secret, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(signingBase, secret).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败", e);
        }
    }
}
