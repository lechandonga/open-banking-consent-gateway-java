package com.github.lechandonga.openbankingconsentgatewayjava.ratelimit;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户维度的固定窗口配额限流。
 *
 * 并发正确性：每个客户一把锁，窗口切换（惰性）与计数扣减在同一临界区内完成，
 * 因此并发请求不会超发（最多放行 limit 个），也不会多扣；窗口切换后重新计数。
 */
@Service
public class QuotaService {

    private record Window(long windowStartEpochSecond, int used) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final GatewayProperties properties;
    private final Clock clock;

    @Autowired
    public QuotaService(GatewayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    QuotaService(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    private Object lockFor(String clientId) {
        return locks.computeIfAbsent(clientId, k -> new Object());
    }

    /**
     * 尝试占用一个配额。成功返回票据（含剩余额度与窗口重置时间），超限抛出异常。
     */
    public QuotaTicket acquire(String clientId) {
        int limit = properties.getQuota().getLimitPerMinute();
        long windowSeconds = 60;
        synchronized (lockFor(clientId)) {
            long nowEpoch = clock.instant().getEpochSecond();
            long currentStart = Math.floorDiv(nowEpoch, windowSeconds) * windowSeconds;
            Instant resetAt = Instant.ofEpochSecond(currentStart + windowSeconds);

            Window w = windows.getOrDefault(clientId, new Window(currentStart, 0));
            if (w.windowStartEpochSecond() != currentStart) {
                // 窗口切换：新窗口从零开始计数
                w = new Window(currentStart, 0);
            }
            if (w.used() >= limit) {
                throw new QuotaExceededException(clientId, limit, resetAt,
                        resetAt.getEpochSecond() - nowEpoch);
            }
            int used = w.used() + 1;
            windows.put(clientId, new Window(currentStart, used));
            return new QuotaTicket(clientId, limit - used, limit, resetAt);
        }
    }

    /** 查询当前窗口使用情况（不扣减）。 */
    public QuotaTicket peek(String clientId) {
        int limit = properties.getQuota().getLimitPerMinute();
        synchronized (lockFor(clientId)) {
            long nowEpoch = clock.instant().getEpochSecond();
            long currentStart = Math.floorDiv(nowEpoch, 60L) * 60;
            Instant resetAt = Instant.ofEpochSecond(currentStart + 60);
            Window w = windows.getOrDefault(clientId, new Window(currentStart, 0));
            int used = w.windowStartEpochSecond() == currentStart ? w.used() : 0;
            return new QuotaTicket(clientId, Math.max(0, limit - used), limit, resetAt);
        }
    }
}
