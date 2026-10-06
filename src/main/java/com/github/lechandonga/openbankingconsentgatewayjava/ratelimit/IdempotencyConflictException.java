package com.github.lechandonga.openbankingconsentgatewayjava.ratelimit;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
