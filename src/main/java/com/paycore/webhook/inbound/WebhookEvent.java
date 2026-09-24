package com.paycore.webhook.inbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    public enum Status { RECEIVED, PROCESSED, IGNORED, FAILED }

    @Id
    private Long id;

    private String provider;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type")
    private String eventType;

    private String payload;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String error;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    protected WebhookEvent() {}

    public void finish(Status status, String error, Instant at) {
        this.status = status;
        this.error = error;
        this.processedAt = at;
    }

    public Long getId() { return id; }
    public String getProvider() { return provider; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
}
