package com.github.lechandonga.openbankingconsentgatewayjava.ratelimit;

import java.time.Instant;

public class QuotaExceededException extends RuntimeException {

    private final String clientId;
    private final int remaining;
    private final int limit;
    private final Instant resetAt;
    private final long retryAfterSeconds;

    public QuotaExceededException(String clientId, int limit, Instant resetAt, long retryAfterSeconds) {
        super("客户 " + clientId + " 请求超出配额，剩余 0 次，窗口重置时间 " + resetAt);
        this.clientId = clientId;
        this.remaining = 0;
        this.limit = limit;
        this.resetAt = resetAt;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getClientId() { return clientId; }
    public int getRemaining() { return remaining; }
    public int getLimit() { return limit; }
    public Instant getResetAt() { return resetAt; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
