package com.github.lechandonga.openbankingconsentgatewayjava.consent.dto;

import java.util.Set;

public class CreateConsentRequest {
    private String clientId;
    private Set<String> scopes;
    private Long ttlSeconds;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public Set<String> getScopes() { return scopes; }
    public void setScopes(Set<String> scopes) { this.scopes = scopes; }
    public Long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
}
