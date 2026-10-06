package com.github.lechandonga.openbankingconsentgatewayjava.business.dto;

import java.util.Set;

public class PaymentRequest {
    private String clientId;
    private String consentId;
    private Set<String> scopes;
    private String paymentInformationId;
    private String creditorAccount;
    private String amount;
    private String currency;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getConsentId() { return consentId; }
    public void setConsentId(String consentId) { this.consentId = consentId; }
    public Set<String> getScopes() { return scopes; }
    public void setScopes(Set<String> scopes) { this.scopes = scopes; }
    public String getPaymentInformationId() { return paymentInformationId; }
    public void setPaymentInformationId(String paymentInformationId) { this.paymentInformationId = paymentInformationId; }
    public String getCreditorAccount() { return creditorAccount; }
    public void setCreditorAccount(String creditorAccount) { this.creditorAccount = creditorAccount; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
