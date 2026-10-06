package com.github.lechandonga.openbankingconsentgatewayjava.consent.dto;

import java.util.Set;

public class AccessRequest {
    private String consentId;
    private String clientId;
    private Set<String> scopes;

    public String getConsentId() { return consentId; }
    public void setConsentId(String consentId) { this.consentId = consentId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public Set<String> getScopes() { return scopes; }
    public void setScopes(Set<String> scopes) { this.scopes = scopes; }
}
