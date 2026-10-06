package com.github.lechandonga.openbankingconsentgatewayjava.event;

import java.time.Instant;
import java.util.Map;

public class BusinessEvent {

    private final String eventId;
    private final String type;
    private final String customerId;
    private final Map<String, Object> payload;
    private final Instant createdAt;
    private volatile EventStatus status = EventStatus.PENDING;
    private volatile int attempts;
    private volatile Instant lastAttemptAt;
    private volatile String lastError;
    private volatile Instant nextAttemptAt;
    private volatile Instant deliveredAt;

    public BusinessEvent(String eventId, String type, String customerId, Map<String, Object> payload) {
        this.eventId = eventId;
        this.type = type;
        this.customerId = customerId;
        this.payload = Map.copyOf(payload);
        this.createdAt = Instant.now();
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    void recordFailure(int attempt, String error, Instant nextAttemptAt) {
        this.attempts = attempt;
        this.lastAttemptAt = Instant.now();
        this.lastError = error;
        this.status = EventStatus.RETRYING;
        this.nextAttemptAt = nextAttemptAt;
    }

    void markDead(int attempt, String error) {
        this.attempts = attempt;
        this.lastAttemptAt = Instant.now();
        this.lastError = error;
        this.status = EventStatus.DEAD;
        this.nextAttemptAt = null;
    }

    void markDelivered(int attempt) {
        this.attempts = attempt;
        this.lastAttemptAt = Instant.now();
        this.status = EventStatus.DELIVERED;
        this.nextAttemptAt = null;
        this.deliveredAt = Instant.now();
    }
}
