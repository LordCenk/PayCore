package com.paycore.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.common.Hashing;
import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** PAYCORE_DESIGN.md, failure scenario 4: duplicate and out-of-order webhooks. */
class InboundWebhookIT extends IntegrationTest {

    private static final String SECRET = "whsec_test"; // application-test.yml

    private ResultActions send(String body, String signature) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/payment")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Processor-Signature", signature)
                .content(body));
    }

    private ResultActions send(String body) throws Exception {
        return send(body, Hashing.hmacSha256Hex(SECRET, body));
    }

    private static String event(String eventId, String type, String reference) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"data\":{\"reference\":\"" + reference
                + "\",\"providerId\":\"mock_ch_webhook\",\"declineCode\":\"CARD_DECLINED\"}}";
    }

    /** A payment stuck in PENDING because the processor never answered the API call. */
    private String[] pendingPayment() throws Exception {
        Fixture f = fixture("tok_timeout_always");
        String id = body(pay(f, "k", 100)).get("id").asString();
        String reference = jdbc.queryForObject("SELECT processor_reference FROM payments WHERE id = ?", String.class, id);
        return new String[] {id, reference};
    }

    private String eventStatus(String eventId) {
        return jdbc.queryForObject("SELECT status FROM webhook_events WHERE event_id = ?", String.class, eventId);
    }

    @Test
    void successWebhookCompletesAPendingPayment() throws Exception {
        String[] p = pendingPayment();

        send(event("evt_1", "payment.succeeded", p[1]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(paymentStatus(p[0])).isEqualTo("SUCCESS");
        assertThat(eventStatus("evt_1")).isEqualTo("PROCESSED");
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE payment_id = ?", p[0])).isEqualTo(2);
    }

    @Test
    void redeliveredWebhookIsStoredOnceAndAppliedOnce() throws Exception {
        String[] p = pendingPayment();
        String body = event("evt_1", "payment.succeeded", p[1]);

        send(body).andExpect(jsonPath("$.duplicate").value(false));
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));

        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE payment_id = ?", p[0])).isEqualTo(2);
    }

    @Test
    void sameOutcomeUnderANewEventIdIsIgnoredAsAlreadyApplied() throws Exception {
        String[] p = pendingPayment();
        send(event("evt_1", "payment.succeeded", p[1]));
        send(event("evt_2", "payment.succeeded", p[1]));

        assertThat(eventStatus("evt_2")).isEqualTo("IGNORED");
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE payment_id = ?", p[0])).isEqualTo(2);
    }

    @Test
    void lateContradictoryEventCannotFlipASuccessfulPayment() throws Exception {
        String[] p = pendingPayment();
        send(event("evt_1", "payment.succeeded", p[1]));

        send(event("evt_2", "payment.failed", p[1])).andExpect(status().isOk());

        assertThat(paymentStatus(p[0])).isEqualTo("SUCCESS");
        assertThat(eventStatus("evt_2")).isEqualTo("IGNORED");
        assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'PROCESSOR_RESULT_CONFLICT'",
                p[0])).isEqualTo(1);
    }

    @Test
    void failureWebhookFailsAPendingPayment() throws Exception {
        String[] p = pendingPayment();
        send(event("evt_1", "payment.failed", p[1]));

        assertThat(paymentStatus(p[0])).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT next_retry_at FROM payments WHERE id = ?", Object.class, p[0])).isNull();
    }

    @Test
    void invalidSignatureIsRejectedAndNotStored() throws Exception {
        String[] p = pendingPayment();
        send(event("evt_1", "payment.succeeded", p[1]), "deadbeef")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"));

        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
        assertThat(paymentStatus(p[0])).isEqualTo("PENDING");
    }

    @Test
    void unknownReferenceIsAcknowledgedButMarkedFailed() throws Exception {
        send(event("evt_1", "payment.succeeded", "ref_unknown")).andExpect(status().isOk());
        assertThat(eventStatus("evt_1")).isEqualTo("FAILED");
    }

    @Test
    void refundWebhookCompletesAPendingRefund() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        String refundId = body(refund(f, paymentId, "r", "{}")).get("id").asString();
        // Put the refund back into PENDING as if the processor call had timed out.
        jdbc.update("UPDATE refunds SET status = 'PENDING' WHERE id = ?", refundId);
        jdbc.update("UPDATE payments SET status = 'REFUND_PENDING' WHERE id = ?", paymentId);
        jdbc.update("DELETE FROM ledger_entries WHERE refund_id = ?", refundId);
        String reference = jdbc.queryForObject("SELECT processor_reference FROM refunds WHERE id = ?", String.class,
                refundId);

        send(event("evt_r1", "refund.succeeded", reference)).andExpect(status().isOk());

        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE refund_id = ?", refundId)).isEqualTo(2);
    }
}
