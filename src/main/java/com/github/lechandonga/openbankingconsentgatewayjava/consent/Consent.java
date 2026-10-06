package com.github.lechandonga.openbankingconsentgatewayjava.consent;

import java.time.Instant;
import java.util.Set;

public class Consent {

    private final String id;
    private final String customerId;
    private final Set<String> scopes;
    private final Instant expiresAt;
    private final Instant createdAt;
    private volatile ConsentStatus status;
    private volatile Instant authorizedAt;
    private volatile Instant revokedAt;

    public Consent(String id, String customerId, Set<String> scopes, Instant expiresAt) {
        this.id = id;
        this.customerId = customerId;
        this.scopes = Set.copyOf(scopes);
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.status = ConsentStatus.PENDING;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    void markAuthorized() {
        this.status = ConsentStatus.AUTHORIZED;
        this.authorizedAt = Instant.now();
    }

    void markRevoked() {
        this.status = ConsentStatus.REVOKED;
        this.revokedAt = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
