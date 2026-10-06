package com.github.lechandonga.openbankingconsentgatewayjava.consent;

public class ConsentAccessDeniedException extends RuntimeException {

    private final ConsentDenyReason reason;
    private final String consentId;

    public ConsentAccessDeniedException(ConsentDenyReason reason, String consentId, String message) {
        super(message);
        this.reason = reason;
        this.consentId = consentId;
    }

    public ConsentDenyReason getReason() { return reason; }
    public String getConsentId() { return consentId; }
}
