package com.github.lechandonga.openbankingconsentgatewayjava.ratelimit;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import com.github.lechandonga.openbankingconsentgatewayjava.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("配额限流业务：按客户固定窗口计数，不超发不多扣")
class QuotaServiceTest {

    private static final int LIMIT = 100;
    private MutableClock clock;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        props.getQuota().setLimitPerMinute(LIMIT);
        clock = MutableClock.startAt(Instant.parse("2026-10-07T00:00:30Z"));
        service = new QuotaService(props, clock);
    }

    @Test
    @DisplayName("超配额拒绝并返回剩余额度(0)与窗口重置时间")
    void rejectWithRemainingAndResetTime() {
        for (int i = 0; i < LIMIT; i++) {
            QuotaTicket ticket = service.acquire("c1");
            assertEquals(LIMIT - i - 1, ticket.remaining());
        }
        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> service.acquire("c1"));
        assertEquals(0, ex.getRemaining());
        assertEquals(LIMIT, ex.getLimit());
        assertEquals(Instant.parse("2026-10-07T00:01:00Z"), ex.getResetAt());
        assertTrue(ex.getRetryAfterSeconds() > 0);
    }

    @Test
    @DisplayName("窗口切换后计数清零，新窗口重新获得完整配额")
    void windowRollover() {
        for (int i = 0; i < LIMIT; i++) {
            service.acquire("c2");
        }
        assertThrows(QuotaExceededException.class, () -> service.acquire("c2"));
        clock.advance(Duration.ofSeconds(31));
        QuotaTicket ticket = service.acquire("c2");
        assertEquals(LIMIT - 1, ticket.remaining());
        assertEquals(Instant.parse("2026-10-07T00:02:00Z"), ticket.resetAt());
    }

    @Test
    @DisplayName("并发请求下严格不超发：成功数恰好等于配额，其余全部被拒")
    void concurrentNoOversell() throws Exception {
        int threads = 500;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    service.acquire("c3");
                    return true;
                } catch (QuotaExceededException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long granted = 0;
        for (var f : results) {
            if (f.get()) {
                granted++;
            }
        }
        assertEquals(LIMIT, granted, "并发下放行数必须恰好等于配额，不得超发");
        assertEquals(0, service.peek("c3").remaining(), "已用计数不得多扣/少扣");
    }

    @Test
    @DisplayName("配额按客户维度相互独立")
    void perClientIsolation() {
        for (int i = 0; i < LIMIT; i++) {
            service.acquire("a");
        }
        assertThrows(QuotaExceededException.class, () -> service.acquire("a"));
        assertEquals(LIMIT - 1, service.acquire("b").remaining());
    }
}
