package com.paycore.payment;

public record CreatePaymentCommand(long amount, String currency, String customerId, String paymentMethodId) {}
