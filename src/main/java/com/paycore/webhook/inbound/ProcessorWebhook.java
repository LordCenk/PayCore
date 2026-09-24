package com.paycore.webhook.inbound;

/**
 * Body the (mock) gateway posts to PayCore, e.g.
 * <pre>{"id":"evt_1","type":"payment.succeeded","data":{"reference":"ref_...","providerId":"mock_ch_..."}}</pre>
 */
public record ProcessorWebhook(String id, String type, Data data) {

    public record Data(String reference, String providerId, String declineCode, String message) {}
}
