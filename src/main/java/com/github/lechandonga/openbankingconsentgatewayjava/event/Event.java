package com.github.lechandonga.openbankingconsentgatewayjava.event;

import java.time.Instant;

/**
 * 业务事件：eventId 全局唯一，同一事件重试投递携带同一 eventId，接收方可据此识别重复。
 */
public class Event {

    private final String eventId;
    private final String type;
    private final String subjectId;
    private final String payloadJson;
    private final Instant createdAt;
    private volatile EventStatus status;
    private volatile int attempts;
    private volatile String lastError;
    private volatile Instant updatedAt;

    public Event(String eventId, String type, String subjectId, String payloadJson, Instant createdAt) {
        this.eventId = eventId;
        this.type = type;
        this.subjectId = subjectId;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = EventStatus.QUEUED;
        this.attempts = 0;
    }

    public String getEventId() { return eventId; }
    public String getType() { return type; }
    public String getSubjectId() { return subjectId; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
    public EventStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(EventStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public void setAttempts(int attempts) { this.attempts = attempts; this.updatedAt = Instant.now(); }
    public void setLastError(String lastError) { this.lastError = lastError; this.updatedAt = Instant.now(); }
}
