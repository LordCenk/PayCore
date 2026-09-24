package com.paycore.payment;

import java.util.LinkedHashMap;
import java.util.Map;

/** Event names and payloads published through the outbox. */
public final class PaymentEvents {

    public static final String CREATED = "PaymentCreated";
    public static final String SUCCEEDED = "PaymentSucceeded";
    public static final String FAILED = "PaymentFailed";
    public static final String CANCELLED = "PaymentCancelled";

    private PaymentEvents() {}

    public static Map<String, Object> data(Payment p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", p.getId());
        data.put("merchantId", p.getMerchantId());
        data.put("customerId", p.getCustomerId());
        data.put("status", p.getStatus().name());
        data.put("amount", p.getAmount());
        data.put("currency", p.getCurrency());
        if (p.getFailureCode() != null) {
            data.put("failureCode", p.getFailureCode());
        }
        return data;
    }
}
