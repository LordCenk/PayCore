package com.paycore.refund;

import java.time.Instant;

public record RefundResponse(String id, String paymentId, long amount, String status, String reason,
                             String providerRefundId, String failureCode, String failureMessage,
                             Instant createdAt, Instant updatedAt) {

    public static RefundResponse of(Refund r) {
        return new RefundResponse(r.getId(), r.getPaymentId(), r.getAmount(), r.getStatus().name(), r.getReason(),
                r.getProviderRefundId(), r.getFailureCode(), r.getFailureMessage(), r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
