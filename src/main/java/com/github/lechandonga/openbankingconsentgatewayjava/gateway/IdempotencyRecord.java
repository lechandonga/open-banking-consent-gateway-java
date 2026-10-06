package com.github.lechandonga.openbankingconsentgatewayjava.gateway;

import java.time.Instant;

/**
 * 幂等记录：键为 customerId + ":" + idempotencyKey。
 * 首次请求的请求摘要与响应在同一把锁内写入，重复提交直接回放，不重复执行副作用。
 */
public class IdempotencyRecord {

    private final String idempotencyKey;
    private final String customerId;
    private final String requestHash;
    private final Instant createdAt;
    private volatile int status;
    private volatile Object response;

    public IdempotencyRecord(String idempotencyKey, String customerId, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.customerId = customerId;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getStatus() {
        return status;
    }

    public Object getResponse() {
        return response;
    }

    void complete(int status, Object response) {
        this.status = status;
        this.response = response;
    }
}
