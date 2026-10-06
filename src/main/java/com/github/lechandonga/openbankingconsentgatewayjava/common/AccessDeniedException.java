package com.github.lechandonga.openbankingconsentgatewayjava.common;

public class AccessDeniedException extends RuntimeException {

    private final DenyReason reason;

    public AccessDeniedException(DenyReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DenyReason getReason() {
        return reason;
    }
}
