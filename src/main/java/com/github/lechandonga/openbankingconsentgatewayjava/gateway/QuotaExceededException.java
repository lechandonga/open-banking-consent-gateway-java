package com.github.lechandonga.openbankingconsentgatewayjava.gateway;

public class QuotaExceededException extends RuntimeException {

    private final int limit;
    private final int remaining;
    private final long resetAtEpochMs;

    public QuotaExceededException(int limit, int remaining, long resetAtEpochMs) {
        super("quota exceeded: limit=" + limit + ", remaining=" + remaining + ", resetAt=" + resetAtEpochMs);
        this.limit = limit;
        this.remaining = remaining;
        this.resetAtEpochMs = resetAtEpochMs;
    }

    public int getLimit() {
        return limit;
    }

    public int getRemaining() {
        return remaining;
    }

    public long getResetAtEpochMs() {
        return resetAtEpochMs;
    }
}
