package com.github.lechandonga.openbankingconsentgatewayjava.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("回调安全业务：签名生成与可校验")
class SignatureUtilTest {

    @Test
    @DisplayName("正确密钥/载荷/头信息可验签通过")
    void verifyValidSignature() {
        String base = SignatureUtil.signingBase("evt-1", "nonce-1", 1728288000000L, "{\"a\":1}");
        String sig = SignatureUtil.sign(base, "secret");
        assertTrue(SignatureUtil.verify(base, "secret", sig));
    }

    @Test
    @DisplayName("错误密钥、篡改载荷或重放旧签名均验签失败")
    void tamperedSignatureFails() {
        String base = SignatureUtil.signingBase("evt-1", "nonce-1", 1728288000000L, "{\"a\":1}");
        String sig = SignatureUtil.sign(base, "secret");
        assertFalse(SignatureUtil.verify(base, "wrong-secret", sig));
        String tampered = SignatureUtil.signingBase("evt-1", "nonce-1", 1728288000000L, "{\"a\":2}");
        assertFalse(SignatureUtil.verify(tampered, "secret", sig));
        String replayedBase = SignatureUtil.signingBase("evt-1", "old-nonce", 1728280000000L, "{\"a\":1}");
        assertFalse(SignatureUtil.verify(replayedBase, "secret", sig));
    }
}
