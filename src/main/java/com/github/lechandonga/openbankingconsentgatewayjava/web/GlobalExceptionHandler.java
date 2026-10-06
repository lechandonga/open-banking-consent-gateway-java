package com.github.lechandonga.openbankingconsentgatewayjava.web;

import com.github.lechandonga.openbankingconsentgatewayjava.consent.ConsentAccessDeniedException;
import com.github.lechandonga.openbankingconsentgatewayjava.ratelimit.IdempotencyConflictException;
import com.github.lechandonga.openbankingconsentgatewayjava.ratelimit.QuotaExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConsentAccessDeniedException.class)
    public ResponseEntity<ApiError> handleConsentDenied(ConsentAccessDeniedException ex) {
        String code = switch (ex.getReason()) {
            case NOT_FOUND -> "CONSENT_NOT_FOUND";
            case NOT_AUTHORIZED -> "CONSENT_NOT_AUTHORIZED";
            case REVOKED -> "CONSENT_REVOKED";
            case EXPIRED -> "CONSENT_EXPIRED";
            case SCOPE_MISMATCH -> "CONSENT_SCOPE_MISMATCH";
        };
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError(code, ex.getMessage(),
                        Map.of("reason", ex.getReason().name(), "consentId", nz(ex.getConsentId()))));
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiError> handleQuota(QuotaExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("QUOTA_EXCEEDED", ex.getMessage(), Map.of(
                        "clientId", ex.getClientId(),
                        "remaining", ex.getRemaining(),
                        "limit", ex.getLimit(),
                        "resetAt", ex.getResetAt().toString(),
                        "retryAfterSeconds", ex.getRetryAfterSeconds())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_REQUEST", ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("IDEMPOTENCY_KEY_REUSED", ex.getMessage(), Map.of()));
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
