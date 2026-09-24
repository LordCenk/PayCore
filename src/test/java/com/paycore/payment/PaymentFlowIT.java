package com.paycore.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PaymentFlowIT extends IntegrationTest {

    @Test
    void successfulPaymentPostsBalancedLedgerAndEvents() throws Exception {
        Fixture f = fixture("tok_success");

        String id = body(pay(f, "order-1", 100_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(100_000))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.providerPaymentId").isNotEmpty()))
                .get("id").asString();

        mvc.perform(get("/api/v1/payments/" + id + "/ledger").header("Authorization", f.merchant().auth()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].entryType").value("DEBIT"))
                .andExpect(jsonPath("$[0].accountId").value("CUSTOMER:" + f.customerId()))
                .andExpect(jsonPath("$[1].entryType").value("CREDIT"))
                .andExpect(jsonPath("$[1].accountId").value("MERCHANT:" + f.merchant().id()));

        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY id",
                String.class, id)).containsExactly("PaymentCreated", "PaymentSucceeded");
        assertThat(jdbc.queryForList("SELECT new_value FROM audit_logs WHERE entity_id = ? AND action = "
                + "'STATUS_CHANGED' ORDER BY id", String.class, id)).containsExactly("PENDING", "SUCCESS");
    }

    @Test
    void declinedPaymentFailsWithoutLedgerEntries() throws Exception {
        Fixture f = fixture("tok_decline");

        String id = body(pay(f, "order-1", 5_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("CARD_DECLINED"))).get("id").asString();

        assertThat(count("SELECT count(*) FROM ledger_entries WHERE payment_id = ?", id)).isZero();
    }

    @Test
    void fraudRuleBlocksPaymentBeforeItReachesTheProcessor() throws Exception {
        Fixture f = fixture("tok_success");

        pay(f, "big", 20_000_001)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("FRAUD_SUSPECTED"))
                .andExpect(jsonPath("$.attemptCount").value(0));
    }

    @Test
    void velocityRuleBlocksTheSixthPaymentInAMinute() throws Exception {
        Fixture f = fixture("tok_success");
        for (int i = 0; i < 5; i++) {
            pay(f, "v-" + i, 100).andExpect(jsonPath("$.status").value("SUCCESS"));
        }
        pay(f, "v-5", 100).andExpect(jsonPath("$.failureCode").value("FRAUD_SUSPECTED"));
    }

    @Test
    void blockedTokenIsRejectedByFraudRules() throws Exception {
        pay(fixture("tok_blocked"), "k", 100).andExpect(jsonPath("$.failureCode").value("FRAUD_SUSPECTED"));
    }

    @Test
    void validationErrors() throws Exception {
        Fixture f = fixture("tok_success");
        pay(f, "neg", -5).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/payments").header("Authorization", f.merchant().auth())
                        .header("Idempotency-Key", "cur").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"currency\":\"JPY\",\"customerId\":\"" + f.customerId()
                                + "\",\"paymentMethodId\":\"" + f.paymentMethodId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_CURRENCY"));

        mvc.perform(post("/api/v1/payments").header("Authorization", f.merchant().auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_HEADER"));
    }

    @Test
    void requestsWithoutAValidApiKeyAreRejected() throws Exception {
        mvc.perform(get("/api/v1/payments/pay_x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments/pay_x").header("Authorization", "Bearer sk_test_wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void merchantsCannotSeeOrUseEachOthersData() throws Exception {
        Fixture a = fixture("tok_success");
        Fixture b = fixture("tok_success");
        String id = payId(a, 100);

        mvc.perform(get("/api/v1/payments/" + id).header("Authorization", b.merchant().auth()))
                .andExpect(status().isNotFound());

        // Merchant B paying with merchant A's customer.
        Fixture stolen = new Fixture(b.merchant(), a.customerId(), a.paymentMethodId());
        pay(stolen, "steal", 100).andExpect(status().isNotFound());
    }

    @Test
    void onlyCreatedPaymentsCanBeCancelled() throws Exception {
        Fixture f = fixture("tok_success");
        String id = payId(f, 100);

        mvc.perform(post("/api/v1/payments/" + id + "/cancel").header("Authorization", f.merchant().auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
    }

    @Test
    void listFiltersByStatus() throws Exception {
        Fixture f = fixture("tok_success");
        payId(f, 100);
        payId(f, 200);

        mvc.perform(get("/api/v1/payments?status=SUCCESS").header("Authorization", f.merchant().auth()))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/payments?status=FAILED").header("Authorization", f.merchant().auth()))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
