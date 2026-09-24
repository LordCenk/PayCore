package com.paycore.fraud;

/** What the fraud rules see about a payment before it is sent to the processor. */
public record FraudContext(String merchantId, String customerId, String paymentMethodToken, long amount,
                           String currency) {}
