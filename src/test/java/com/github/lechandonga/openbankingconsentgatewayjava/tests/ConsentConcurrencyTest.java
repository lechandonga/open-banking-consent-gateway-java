package com.github.lechandonga.openbankingconsentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.lechandonga.openbankingconsentgatewayjava.common.AccessDeniedException;
import com.github.lechandonga.openbankingconsentgatewayjava.common.DenyReason;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.Consent;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.ConsentService;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.ConsentStatus;
import com.github.lechandonga.openbankingconsentgatewayjava.consent.ConsentStore;

/**
 * 授权生命周期与“撤销/访问竞争”测试。
 */
class ConsentConcurrencyTest {

    private static final String BIZ = "授权管理";

    private ConsentService newService(Clock clock) {
        return new ConsentService(new ConsentStore(clock), clock);
    }

    @Test
    void lifecycleAndDenyReasons() {
        TestLog.business(BIZ, "授权状态机与可区分拒绝原因");
        ConsentService service = newService(Clock.systemUTC());

        TestLog.caseNo(BIZ, 1, "未授权(PENDING)访问被拒绝，原因 UNAUTHORIZED");
        Consent consent = service.create("cust-1", Set.of("accounts.read", "payments.initiate"), 3600);
        AccessDeniedException ex1 = assertThrows(AccessDeniedException.class,
                () -> service.checkAccess(consent.getId(), "accounts.read"));
        assertEquals(DenyReason.UNAUTHORIZED, ex1.getReason());
        TestLog.pass("reason=" + ex1.getReason());

        TestLog.caseNo(BIZ, 2, "授权后访问放行；范围不匹配返回 SCOPE_MISMATCH");
        service.authorize(consent.getId());
        Consent accessed = service.checkAccess(consent.getId(), "accounts.read");
        assertEquals(ConsentStatus.AUTHORIZED, accessed.getStatus());
        AccessDeniedException ex2 = assertThrows(AccessDeniedException.class,
                () -> service.checkAccess(consent.getId(), "cards.read"));
        assertEquals(DenyReason.SCOPE_MISMATCH, ex2.getReason());
        TestLog.pass("命中范围放行，越界访问 reason=" + ex2.getReason());

        TestLog.caseNo(BIZ, 3, "撤销后访问被拒绝，原因 REVOKED；重复撤销幂等");
        service.revoke(consent.getId());
        service.revoke(consent.getId());
        AccessDeniedException ex3 = assertThrows(AccessDeniedException.class,
                () -> service.checkAccess(consent.getId(), "accounts.read"));
        assertEquals(DenyReason.REVOKED, ex3.getReason());
        TestLog.pass("reason=" + ex3.getReason() + "，重复撤销不报错");

        TestLog.caseNo(BIZ, 4, "过期授权访问被拒绝，原因 EXPIRED");
        Clock creationClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        Clock accessClock = Clock.fixed(Instant.now().plusSeconds(7200), ZoneOffset.UTC);
        ConsentService expiredService = new ConsentService(creationClock, accessClock);
        Consent expired = expiredService.create("cust-1", Set.of("accounts.read"), 3600);
        expiredService.authorize(expired.getId());
        AccessDeniedException ex4 = assertThrows(AccessDeniedException.class,
                () -> expiredService.checkAccess(expired.getId(), "accounts.read"));
        assertEquals(DenyReason.EXPIRED, ex4.getReason());
        TestLog.pass("reason=" + ex4.getReason());

        TestLog.caseNo(BIZ, 5, "授权不存在返回 UNAUTHORIZED；撤销后不能重新授权");
        assertThrows(AccessDeniedException.class,
                () -> service.checkAccess("missing", "accounts.read"));
        assertThrows(IllegalStateException.class, () -> service.authorize(consent.getId()));
        TestLog.pass("不存在授权被拒绝，REVOKED 为终态");
    }

    @Test
    void revokeVsAccessRace() throws InterruptedException {
        TestLog.business(BIZ, "撤销与访问并发竞争");
        TestLog.caseNo(BIZ, 6, "50 个并发访问与 1 个撤销竞争：撤销后无请求放行，无半撤销状态");

        ConsentService service = newService(Clock.systemUTC());
        Consent consent = service.create("cust-race", Set.of("accounts.read"), 3600);
        service.authorize(consent.getId());

        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger revokedDenied = new AtomicInteger();
        AtomicInteger otherDenied = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.checkAccess(consent.getId(), "accounts.read");
                    allowed.incrementAndGet();
                } catch (AccessDeniedException e) {
                    if (e.getReason() == DenyReason.REVOKED) {
                        revokedDenied.incrementAndGet();
                    } else {
                        otherDenied.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                service.revoke(consent.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        TestLog.info("放行=" + allowed.get() + "，因 REVOKED 拒绝=" + revokedDenied.get()
                + "，其他拒绝=" + otherDenied.get());
        assertEquals(0, otherDenied.get(), "不应出现 REVOKED 以外的拒绝原因");
        assertEquals(threads, allowed.get() + revokedDenied.get());
        assertEquals(ConsentStatus.REVOKED, service.get(consent.getId()).getStatus());

        TestLog.caseNo(BIZ, 7, "竞争结束后的后续访问一律被拒绝");
        assertThrows(AccessDeniedException.class,
                () -> service.checkAccess(consent.getId(), "accounts.read"));
        TestLog.pass("最终状态 REVOKED，后续访问拒绝");
    }
}
