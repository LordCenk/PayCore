package com.paycore.webhook.outbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One event to POST to one merchant's webhook URL, retried with backoff until delivered or given up. */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    public enum Status { PENDING, DELIVERED, FAILED }

    @Id
    private Long id;

    @Column(name = "merchant_id")
    private String merchantId;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_type")
    private String eventType;

    private String payload;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "attempt_count")
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_response_code")
    private Integer lastResponseCode;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected WebhookDelivery() {}

    void lease(Instant until, Instant now) {
        nextAttemptAt = until;
        updatedAt = now;
    }

    void recordAttempt(boolean delivered, Integer responseCode, String error, int maxAttempts, Instant retryAt,
                       Instant now) {
        attemptCount++;
        lastResponseCode = responseCode;
        lastError = error;
        updatedAt = now;
        if (delivered) {
            status = Status.DELIVERED;
        } else if (attemptCount >= maxAttempts) {
            status = Status.FAILED;
        } else {
            nextAttemptAt = retryAt;
        }
    }

    public Long getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Integer getLastResponseCode() { return lastResponseCode; }
}
