package com.paycore.refund;

import com.paycore.payment.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private String id;

    @Column(name = "payment_id")
    private String paymentId;

    private long amount;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    private String reason;

    @Column(name = "processor_reference")
    private String processorReference;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    @Version
    private Long version;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Refund() {}

    public Refund(String id, String paymentId, long amount, String reason, String processorReference, Instant now) {
        this.id = id;
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.processorReference = processorReference;
        this.status = RefundStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void succeeded(String providerRefundId, Instant now) {
        requirePending(RefundStatus.SUCCEEDED);
        this.status = RefundStatus.SUCCEEDED;
        this.providerRefundId = providerRefundId;
        this.updatedAt = now;
    }

    public void failed(String code, String message, Instant now) {
        requirePending(RefundStatus.FAILED);
        this.status = RefundStatus.FAILED;
        this.failureCode = code;
        this.failureMessage = message;
        this.updatedAt = now;
    }

    private void requirePending(RefundStatus next) {
        if (status != RefundStatus.PENDING) {
            throw new InvalidStateTransitionException("Refund", id, status, next);
        }
    }

    public String getId() { return id; }
    public String getPaymentId() { return paymentId; }
    public long getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getProcessorReference() { return processorReference; }
    public String getProviderRefundId() { return providerRefundId; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
