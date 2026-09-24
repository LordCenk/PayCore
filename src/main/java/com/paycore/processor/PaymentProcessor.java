package com.paycore.processor;

import java.util.Optional;

/**
 * The external payment gateway. Every call carries a PayCore-generated reference that the gateway
 * treats as an idempotency key: repeating a call with the same reference never moves money twice.
 */
public interface PaymentProcessor {

    String name();

    ChargeResult charge(ChargeRequest request) throws ProcessorTimeoutException;

    /** What the gateway knows about a charge; empty if it never received it. */
    Optional<ChargeResult> getCharge(String reference) throws ProcessorTimeoutException;

    RefundResult refund(RefundRequest request) throws ProcessorTimeoutException;

    Optional<RefundResult> getRefund(String reference) throws ProcessorTimeoutException;

    record ChargeRequest(String reference, long amount, String currency, String paymentMethodToken) {}

    record RefundRequest(String reference, String chargeReference, long amount, String currency) {}

    enum Outcome { SUCCEEDED, DECLINED }

    record ChargeResult(Outcome outcome, String providerPaymentId, String declineCode, String message) {}

    record RefundResult(Outcome outcome, String providerRefundId, String declineCode, String message) {}
}
