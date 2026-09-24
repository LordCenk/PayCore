package com.paycore.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Refund lifecycle, plus PAYCORE_DESIGN.md failure scenario 5 (refund during processing). */
class RefundIT extends IntegrationTest {

    @Test
    void fullRefundReversesTheLedger() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100_000);

        String refundId = body(refund(f, paymentId, "r-1", "{\"reason\":\"returned\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amount").value(100_000))).get("id").asString();

        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
        mvc.perform(get("/api/v1/refunds/" + refundId).header("Authorization", f.merchant().auth()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        // Two transactions, each balanced; merchant and customer both net to zero.
        assertThat(count("SELECT count(DISTINCT transaction_id) FROM ledger_entries WHERE payment_id = ?", paymentId))
                .isEqualTo(2);
        Long merchantNet = jdbc.queryForObject("SELECT sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END)"
                + " FROM ledger_entries WHERE account_id = ?", Long.class, "MERCHANT:" + f.merchant().id());
        assertThat(merchantNet).isZero();
    }

    @Test
    void refundOfAPendingPaymentIsRejected() throws Exception {
        Fixture f = fixture("tok_timeout_always");
        String paymentId = body(pay(f, "k", 100)).get("id").asString();

        refund(f, paymentId, "r-1", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
        assertThat(paymentStatus(paymentId)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM refunds")).isZero();
    }

    @Test
    void refundedPaymentCannotBeRefundedAgain() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        refund(f, paymentId, "r-1", "{}").andExpect(status().isCreated());

        refund(f, paymentId, "r-2", "{}").andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM refunds")).isEqualTo(1);
    }

    @Test
    void sameRefundKeyIsReplayedNotRepeated() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        String first = body(refund(f, paymentId, "r-1", "{}")).get("id").asString();

        refund(f, paymentId, "r-1", "{}").andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first));
        assertThat(count("SELECT count(*) FROM refunds")).isEqualTo(1);
    }

    @Test
    void declinedRefundReturnsThePaymentToSuccess() throws Exception {
        Fixture f = fixture("tok_refund_decline");
        String paymentId = payId(f, 100);

        refund(f, paymentId, "r-1", "{}")
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("REFUND_DECLINED"));
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCESS");
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE refund_id IS NOT NULL")).isZero();

        // A failed refund doesn't block a new attempt.
        refund(f, paymentId, "r-2", "{}").andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM refunds WHERE payment_id = ?", paymentId)).isEqualTo(2);
    }

    @Test
    void partialAndOversizedRefundsAreRejected() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 1_000);

        refund(f, paymentId, "r-1", "{\"amount\":500}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PARTIAL_REFUND_NOT_SUPPORTED"));
        refund(f, paymentId, "r-2", "{\"amount\":5000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REFUND_EXCEEDS_PAYMENT"));
        assertThat(paymentStatus(paymentId)).isEqualTo("SUCCESS");
    }

    @Test
    void concurrentRefundsWithDifferentKeysRefundOnce() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "r-" + i;
            results.add(pool.submit(() -> {
                start.await();
                return refund(f, paymentId, key, "{}").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> r : results) {
            statuses.add(r.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(threads - 1);
        assertThat(count("SELECT count(*) FROM refunds")).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("REFUNDED");
    }
}
