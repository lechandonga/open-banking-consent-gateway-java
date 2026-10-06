package com.github.lechandonga.openbankingconsentgatewayjava.gateway;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;

/**
 * 按客户维度的固定窗口配额。
 * acquire 与查询都在每客户锁内进行：并发请求要么拿到额度，要么一起看到准确剩余量。
 */
@Service
public class QuotaService {

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, Object> customerLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> counters = new ConcurrentHashMap<>();

    public QuotaService(GatewayProperties properties) {
        this.properties = properties;
    }

    private Object lockFor(String customerId) {
        return customerLocks.computeIfAbsent(customerId, key -> new Object());
    }

    private long windowStartOf(long now) {
        return now - (now % properties.getQuota().getWindowMs());
    }

    /**
     * 消耗一个额度；窗口滚动时原子重置计数器。
     */
    public QuotaWindow acquire(String customerId) {
        synchronized (lockFor(customerId)) {
            long now = System.currentTimeMillis();
            long start = windowStartOf(now);
            long[] counter = counters.computeIfAbsent(customerId, key -> new long[]{start, 0});
            if (counter[0] != start) {
                counter[0] = start;
                counter[1] = 0;
            }
            int limit = properties.getQuota().getLimit();
            if (counter[1] >= limit) {
                long resetAt = start + properties.getQuota().getWindowMs();
                throw new QuotaExceededException(limit, 0, resetAt);
            }
            counter[1]++;
            return new QuotaWindow(start, (int) counter[1]);
        }
    }

    /**
     * 查询当前窗口用量（不扣额度）。
     */
    public QuotaWindow peek(String customerId) {
        synchronized (lockFor(customerId)) {
            long now = System.currentTimeMillis();
            long start = windowStartOf(now);
            long[] counter = counters.get(customerId);
            int used = (counter == null || counter[0] != start) ? 0 : (int) counter[1];
            return new QuotaWindow(start, used);
        }
    }

    public int getLimit() {
        return properties.getQuota().getLimit();
    }

    public long getWindowMs() {
        return properties.getQuota().getWindowMs();
    }
}
