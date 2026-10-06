package com.github.lechandonga.openbankingconsentgatewayjava.consent.dto;

import com.github.lechandonga.openbankingconsentgatewayjava.consent.Consent;

import java.time.Instant;
import java.util.Set;

public class ConsentView {
    private final String consentId;
    private final String clientId;
    private final Set<String> scopes;
    private final String status;
    private final Instant expiresAt;
    private final long version;

    public ConsentView(Consent c) {
        this.consentId = c.getId();
        this.clientId = c.getClientId();
        this.scopes = c.getScopes();
        this.status = c.getStatus().name();
        this.expiresAt = c.getExpiresAt();
        this.version = c.getVersion();
    }

    public String getConsentId() { return consentId; }
    public String getClientId() { return clientId; }
    public Set<String> getScopes() { return scopes; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
}
