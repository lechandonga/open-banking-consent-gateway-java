package com.github.lechandonga.openbankingconsentgatewayjava.consent;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lechandonga.openbankingconsentgatewayjava.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("授权业务：创建、生命周期与访问拒绝原因")
class ConsentServiceTest {

    private MutableClock clock;
    private ConsentService service;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        clock = MutableClock.startAt(Instant.parse("2026-10-07T00:00:00Z"));
        service = new ConsentService(props, clock);
    }

    private String authorizedConsent(String clientId, Set<String> scopes, long ttl) {
        Consent c = service.createConsent(clientId, scopes, ttl);
        service.authorize(c.getId());
        return c.getId();
    }

    @Nested
    @DisplayName("访问拒绝原因可区分")
    class DenyReasons {

        @Test
        @DisplayName("授权不存在 -> NOT_FOUND")
        void notFound() {
            ConsentAccessDeniedException ex = assertThrows(ConsentAccessDeniedException.class,
                    () -> service.checkAccess("missing", "c1", Set.of("payments")));
            assertEquals(ConsentDenyReason.NOT_FOUND, ex.getReason());
        }

        @Test
        @DisplayName("已创建但未授权 -> NOT_AUTHORIZED")
        void notAuthorized() {
            Consent c = service.createConsent("c1", Set.of("payments"), 600L);
            ConsentAccessDeniedException ex = assertThrows(ConsentAccessDeniedException.class,
                    () -> service.checkAccess(c.getId(), "c1", Set.of("payments")));
            assertEquals(ConsentDenyReason.NOT_AUTHORIZED, ex.getReason());
        }

        @Test
        @DisplayName("撤销后访问 -> REVOKED")
        void revoked() {
            String id = authorizedConsent("c1", Set.of("payments"), 600L);
            service.revoke(id);
            ConsentAccessDeniedException ex = assertThrows(ConsentAccessDeniedException.class,
                    () -> service.checkAccess(id, "c1", Set.of("payments")));
            assertEquals(ConsentDenyReason.REVOKED, ex.getReason());
        }

        @Test
        @DisplayName("超过有效期访问 -> EXPIRED")
        void expired() {
            String id = authorizedConsent("c1", Set.of("payments"), 60L);
            clock.advance(Duration.ofSeconds(61));
            ConsentAccessDeniedException ex = assertThrows(ConsentAccessDeniedException.class,
                    () -> service.checkAccess(id, "c1", Set.of("payments")));
            assertEquals(ConsentDenyReason.EXPIRED, ex.getReason());
        }

        @Test
        @DisplayName("客户不匹配或范围不足 -> SCOPE_MISMATCH")
        void scopeMismatch() {
            String id = authorizedConsent("c1", Set.of("payments"), 600L);
            assertEquals(ConsentDenyReason.SCOPE_MISMATCH, assertThrows(ConsentAccessDeniedException.class,
                    () -> service.checkAccess(id, "other-client", Set.of("payments"))).getReason());
            assertEquals(ConsentDenyReason.SCOPE_MISMATCH, assertThrows(ConsentAccessDeniedException.class,
                    () -> service.checkAccess(id, "c1", Set.of("payments", "accounts"))).getReason());
        }

        @Test
        @DisplayName("授权在有效期内且范围匹配 -> 放行")
        void allowed() {
            String id = authorizedConsent("c1", Set.of("payments", "accounts"), 600L);
            Consent c = service.checkAccess(id, "c1", Set.of("accounts"));
            assertEquals(ConsentStatus.AUTHORIZED, c.getStatus());
        }
    }

    @Test
    @DisplayName("撤销与访问并发竞争：撤销后的请求一律拒绝，不允许只撤销一半的中间状态")
    void revokeRaceWithAccess() throws Exception {
        int total = 400;
        int allowed = 0;
        int denied = 0;
        int inconsistent = 0;

        for (int round = 0; round < total; round++) {
            final String clientId = "client-" + round;
            final String id = authorizedConsent(clientId, Set.of("payments"), 3600L);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            final AtomicInteger roundAllowed = new AtomicInteger();
            final AtomicInteger roundDenied = new AtomicInteger();
            try {
                pool.submit(() -> {
                    await(start);
                    service.revoke(id);
                });
                pool.submit(() -> {
                    await(start);
                    try {
                        service.checkAccess(id, clientId, Set.of("payments"));
                        roundAllowed.incrementAndGet();
                    } catch (ConsentAccessDeniedException e) {
                        roundDenied.incrementAndGet();
                    }
                });
                start.countDown();
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }

            // 撤销一定最终生效；之后访问必须全部 REVOKED
            assertEquals(ConsentStatus.REVOKED, service.get(id).getStatus(), "撤销必须完整生效");
            ConsentAccessDeniedException after = assertThrows(ConsentAccessDeniedException.class,
                    () -> service.checkAccess(id, clientId, Set.of("payments")),
                    "撤销之后一律不得放行");
            assertEquals(ConsentDenyReason.REVOKED, after.getReason());
            // 竞争结果只能是"访问先于撤销（1 放行）"或"撤销先于访问（0 放行）"
            if (roundAllowed.get() > 1 || (roundAllowed.get() == 1 && roundDenied.get() != 0)) {
                inconsistent++;
            }
            allowed += roundAllowed.get();
            denied += roundDenied.get();
        }

        assertTrue(allowed > 0, "应当存在访问先于撤销而放行的情形，实际=" + allowed);
        assertTrue(denied > 0, "应当存在撤销先于访问而拒绝的情形，实际=" + denied);
        assertEquals(0, inconsistent, "不允许出现只撤销一半的中间状态");
        System.out.printf("撤销竞争: 总轮次=%d 放行=%d 拒绝=%d 不一致=%d%n", total, allowed, denied, inconsistent);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
