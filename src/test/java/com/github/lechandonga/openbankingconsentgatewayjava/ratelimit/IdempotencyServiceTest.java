package com.github.lechandonga.openbankingconsentgatewayjava.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("幂等业务：重复提交复用结果且副作用只执行一次")
class IdempotencyServiceTest {

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(new ObjectMapper());
    }

    @Test
    @DisplayName("相同幂等键的重复提交返回首次结果，业务副作用只执行一次")
    void replayReturnsFirstResult() {
        AtomicInteger sideEffects = new AtomicInteger();
        Map<String, Object> body = Map.of("amount", "10.00");

        IdempotencyService.ExecutedResponse first = service.execute("c1", "key-1", body,
                () -> {
                    sideEffects.incrementAndGet();
                    return new IdempotencyService.ExecutedResponse(201, "{\"no\":1}", false);
                });
        IdempotencyService.ExecutedResponse second = service.execute("c1", "key-1", body,
                () -> {
                    sideEffects.incrementAndGet();
                    return new IdempotencyService.ExecutedResponse(201, "{\"no\":2}", false);
                });

        assertFalse(first.replay());
        assertTrue(second.replay());
        assertEquals("{\"no\":1}", second.body(), "重复提交必须返回首次结果");
        assertEquals(1, sideEffects.get(), "业务副作用不得重复执行");
    }

    @Test
    @DisplayName("幂等键相同但请求内容不同 -> 明确拒绝 IDEMPOTENCY 冲突")
    void sameKeyDifferentBodyRejected() {
        service.execute("c1", "key-2", Map.of("amount", "10"),
                () -> new IdempotencyService.ExecutedResponse(201, "ok", false));
        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                () -> service.execute("c1", "key-2", Map.of("amount", "999"),
                        () -> new IdempotencyService.ExecutedResponse(201, "other", false)));
        assertTrue(ex.getMessage().contains("已用于不同的请求内容"));
    }

    @Test
    @DisplayName("并发重复提交（同键同内容）：只有首个请求执行业务，其余全部复用")
    void concurrentDuplicatesExecuteOnce() throws Exception {
        int threads = 32;
        AtomicInteger sideEffects = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Map<String, Object> body = Map.of("from", "acc-1", "to", "acc-2", "amount", "88.88");

        var futures = new java.util.ArrayList<java.util.concurrent.Future<IdempotencyService.ExecutedResponse>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.execute("c1", "concurrent-key", body, () -> {
                    sideEffects.incrementAndGet();
                    return new IdempotencyService.ExecutedResponse(201, "FIRST-RESULT", false);
                });
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, sideEffects.get(), "并发下业务副作用必须只执行一次");
        int replays = 0;
        for (var f : futures) {
            IdempotencyService.ExecutedResponse r = f.get();
            assertEquals("FIRST-RESULT", r.body());
            if (r.replay()) {
                replays++;
            }
        }
        assertEquals(threads - 1, replays, "除首个请求外其余都应命中幂等缓存");
    }
}
