package com.github.lechandonga.openbankingconsentgatewayjava.web;

import java.time.Instant;
import java.util.Map;

public class ApiError {
    private final Instant timestamp = Instant.now();
    private final String code;
    private final String message;
    private final Map<String, Object> details;

    public ApiError(String code, String message, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.details = details;
    }

    public Instant getTimestamp() { return timestamp; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public Map<String, Object> getDetails() { return details; }
}
