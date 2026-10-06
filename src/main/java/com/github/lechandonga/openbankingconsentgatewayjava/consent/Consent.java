package com.github.lechandonga.openbankingconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Set;

/**
 * 授权聚合：绑定客户、访问范围与有效期。version 用于乐观并发控制，
 * 保证撤销与访问竞争时不会出现只撤销一半的中间状态。
 */
public class Consent {

    private final String id;
    private final String clientId;
    private final Set<String> scopes;
    private final Instant expiresAt;
    private volatile ConsentStatus status;
    private volatile long version;

    public Consent(String id, String clientId, Set<String> scopes, Instant expiresAt,
                   ConsentStatus status, long version) {
        this.id = id;
        this.clientId = clientId;
        this.scopes = Set.copyOf(scopes);
        this.expiresAt = expiresAt;
        this.status = status;
        this.version = version;
    }

    public String getId() { return id; }
    public String getClientId() { return clientId; }
    public Set<String> getScopes() { return scopes; }
    public Instant getExpiresAt() { return expiresAt; }
    public ConsentStatus getStatus() { return status; }
    public long getVersion() { return version; }

    public void setStatus(ConsentStatus status) { this.status = status; }
    public void setVersion(long version) { this.version = version; }

    /**
     * 派生状态：已授权且当前时间超过有效期则视为 EXPIRED（不修改持久状态）。
     */
    public boolean isExpired(Instant now) {
        return status == ConsentStatus.AUTHORIZED && !now.isBefore(expiresAt);
    }
}
