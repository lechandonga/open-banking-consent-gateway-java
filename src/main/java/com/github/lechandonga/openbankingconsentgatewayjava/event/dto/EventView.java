package com.github.lechandonga.openbankingconsentgatewayjava.event.dto;

import com.github.lechandonga.openbankingconsentgatewayjava.event.Event;

import java.time.Instant;

public class EventView {
    private final String eventId;
    private final String type;
    private final String subjectId;
    private final String status;
    private final int attempts;
    private final String lastError;
    private final Instant createdAt;
    private final Instant updatedAt;

    public EventView(Event e) {
        this.eventId = e.getEventId();
        this.type = e.getType();
        this.subjectId = e.getSubjectId();
        this.status = e.getStatus().name();
        this.attempts = e.getAttempts();
        this.lastError = e.getLastError();
        this.createdAt = e.getCreatedAt();
        this.updatedAt = e.getUpdatedAt();
    }

    public String getEventId() { return eventId; }
    public String getType() { return type; }
    public String getSubjectId() { return subjectId; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
