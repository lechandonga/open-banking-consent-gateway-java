package com.github.lechandonga.openbankingconsentgatewayjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lechandonga.openbankingconsentgatewayjava.gateway.GatewayService;
import com.github.lechandonga.openbankingconsentgatewayjava.gateway.IdempotencyConflictException;
import com.github.lechandonga.openbankingconsentgatewayjava.gateway.QuotaExceededException;
import com.github.lechandonga.openbankingconsentgatewayjava.gateway.QuotaService;

/**
 * 幂等与配额限流测试，含并发重复提交与并发抢额度。
 */
class IdempotencyQuotaTest {

    private static final String BIZ = "幂等与配额";

    private GatewayProperties props(int limit, long windowMs) {
        GatewayProperties properties = new GatewayProperties();
        properties.getQuota().setLimit(limit);
        properties.getQuota().setWindowMs(windowMs);
        return properties;
    }

    @Test
    void idempotentReplayExecutesOnce() throws InterruptedException {
        TestLog.business(BIZ, "相同幂等键重复提交只执行一次副作用");
        GatewayService gateway = new GatewayService(new QuotaService(props(100, 60_000)));
        AtomicInteger sideEffects = new AtomicInteger();

        TestLog.caseNo(BIZ, 1, "30 线程用相同幂等键+相同内容并发提交，只有 1 次真实执行且结果一致");
        int threads = 30;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<GatewayService.Submission> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    GatewayService.Submission submission = gateway.submit("cust-a", "key-1",
                            "{\"amount\":100}", sideEffects::incrementAndGet);
                    synchronized (results) {
                        results.add(submission);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threads, results.size());
        long firstCount = results.stream().filter(s -> !s.replay()).count();
        long replays = results.stream().filter(GatewayService.Submission::replay).count();
        Set<Object> bodies = new HashSet<>(results.stream().map(GatewayService.Submission::body).toList());

        TestLog.info("首次执行=" + firstCount + "，回放=" + replays + "，副作用次数=" + sideEffects.get());
        assertEquals(1, sideEffects.get(), "业务副作用只允许执行一次");
        assertEquals(1, firstCount);
        assertEquals(threads - 1, replays);
        assertEquals(1, bodies.size(), "所有返回体必须与首次一致");
        TestLog.pass("副作用=1，" + (threads - 1) + " 次重复提交均回放首次结果");

        TestLog.caseNo(BIZ, 2, "相同幂等键但请求内容不同，明确冲突拒绝");
        assertThrows(IdempotencyConflictException.class,
                () -> gateway.submit("cust-a", "key-1", "{\"amount\":999}", sideEffects::incrementAndGet));
        TestLog.pass("不同内容抛 IDEMPOTENCY 冲突，副作用仍=" + sideEffects.get());
    }

    @Test
    void quotaNoOversellUnderConcurrency() throws InterruptedException {
        TestLog.business(BIZ, "固定窗口配额在并发下不超发、不多扣");
        int limit = 5;
        GatewayService gateway = new GatewayService(new QuotaService(props(limit, 60_000)));
        gateway.submit("cust-b", "key-seed", "p-seed", () -> "seed");

        TestLog.caseNo(BIZ, 3, "预置 1 个请求后，" + (limit - 1)
                + " 个并发新请求恰好填满窗口，其余 429 且携带剩余额度/重置时间");
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<QuotaExceededException> quotaErrors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    gateway.submit("cust-b", "key-" + idx, "p-" + idx, () -> "ok");
                    success.incrementAndGet();
                } catch (QuotaExceededException e) {
                    rejected.incrementAndGet();
                    synchronized (quotaErrors) {
                        quotaErrors.add(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        TestLog.info("成功=" + success.get() + "，拒绝=" + rejected.get());
        assertEquals(limit - 1, success.get(), "不能超发");
        assertEquals(threads - (limit - 1), rejected.get());
        for (QuotaExceededException error : quotaErrors) {
            assertEquals(limit, error.getLimit());
            assertEquals(0, error.getRemaining(), "超额时剩余额度必须为 0");
            assertTrue(error.getResetAtEpochMs() > System.currentTimeMillis(), "重置时间必须在未来");
        }
        TestLog.pass("总成功数=" + limit + "，拒绝响应含 limit/remaining=0/resetAt");

        TestLog.caseNo(BIZ, 4, "幂等回放不扣减额度");
        int beforeUsed = (Integer) gateway.quotaStatus("cust-b").get("used");
        // 窗口已满：只有已存在记录的回放才能通过，且不得扣减额度
        gateway.submit("cust-b", "key-seed", "p-seed", () -> {
            throw new AssertionError("回放不得再次执行业务");
        });
        int afterUsed = (Integer) gateway.quotaStatus("cust-b").get("used");
        assertEquals(beforeUsed, afterUsed);
        TestLog.pass("窗口已满时回放仍放行且 used 保持=" + afterUsed);
    }

    @Test
    void quotaWindowRollover() throws InterruptedException {
        TestLog.business(BIZ, "窗口切换后额度重置");
        TestLog.caseNo(BIZ, 5, "短窗口用尽后，下一窗口额度恢复且不出现跨窗口多扣");
        GatewayService gateway = new GatewayService(new QuotaService(props(2, 120)));
        gateway.submit("cust-c", "k1", "a", () -> "ok");
        gateway.submit("cust-c", "k2", "b", () -> "ok");
        assertThrows(QuotaExceededException.class, () -> gateway.submit("cust-c", "k3", "c", () -> "ok"));
        Map<String, Object> status = gateway.quotaStatus("cust-c");
        TestLog.info("窗口1用尽: used=" + status.get("used") + ", remaining=" + status.get("remaining"));

        Thread.sleep(260);
        gateway.submit("cust-c", "k4", "d", () -> "ok");
        Map<String, Object> rolled = gateway.quotaStatus("cust-c");
        assertEquals(1, rolled.get("used"), "新窗口应从 0 开始计数");
        assertEquals(1, rolled.get("remaining"));
        TestLog.pass("新窗口 used=1, remaining=1");
    }
}
