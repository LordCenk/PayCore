package com.paycore.payment;

import java.time.Instant;

public record PaymentResponse(
        String id,
        String status,
        long amount,
        String currency,
        String customerId,
        String paymentMethodId,
        String provider,
        String providerPaymentId,
        String failureCode,
        String failureMessage,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse of(Payment p) {
        return new PaymentResponse(p.getId(), p.getStatus().name(), p.getAmount(), p.getCurrency(),
                p.getCustomerId(), p.getPaymentMethodId(), p.getProvider(), p.getProviderPaymentId(),
                p.getFailureCode(), p.getFailureMessage(), p.getAttemptCount(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
