package com.paycore.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.processor.MockPaymentProcessor;
import com.paycore.processor.PaymentProcessor;
import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** PAYCORE_DESIGN.md, failure scenarios 2 (processor timeout) and 3 (crash after processor success). */
class RetryIT extends IntegrationTest {

    @Autowired
    PaymentRetryJob retryJob;

    @Autowired
    PaymentStateService paymentState;

    @Autowired
    MockPaymentProcessor processor;

    @Autowired
    PaymentRepository payments;

    @Test
    void timeoutAfterChargeIsResolvedByAskingTheProcessorNotByChargingAgain() throws Exception {
        Fixture f = fixture("tok_timeout_after_charge");

        String id = body(pay(f, "k", 100_000)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failureCode").value("PROCESSOR_TIMEOUT"))).get("id").asString();

        retryJob.runOnce();

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getAttemptCount()).isEqualTo(2);
        assertThat(payment.getFailureCode()).isNull();
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE payment_id = ?", id)).isEqualTo(2);
    }

    @Test
    void timeoutBeforeChargeIsRetried() throws Exception {
        Fixture f = fixture("tok_timeout");
        String id = body(pay(f, "k", 100).andExpect(status().isAccepted())).get("id").asString();

        retryJob.runOnce();

        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
    }

    @Test
    void paymentFailsOnlyAfterRetriesAreExhaustedAndTheProcessorHasNoRecord() throws Exception {
        Fixture f = fixture("tok_timeout_always");
        String id = body(pay(f, "k", 100).andExpect(status().isAccepted())).get("id").asString();

        for (int i = 0; i < 4; i++) {
            retryJob.runOnce();
            assertThat(paymentStatus(id)).as("after retry %d", i + 1).isEqualTo("PENDING");
        }
        retryJob.runOnce(); // attempt 6 > max 5: processor confirms no charge

        Payment payment = payments.findById(id).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(payment.getNextRetryAt()).isNull();
    }

    @Test
    void crashAfterProcessorSuccessIsRecoveredWithoutASecondCharge() throws Exception {
        Fixture f = fixture("tok_success");
        // tx1 committed: payment is PENDING with its processor reference.
        Payment payment = paymentState.create(f.merchant().id(),
                new CreatePaymentCommand(100_000, "INR", f.customerId(), f.paymentMethodId()), null, "test");
        paymentState.claimAttempt(payment.getId(), false);
        // The processor charged the card, then PayCore "crashed" before recording the result.
        PaymentProcessor.ChargeResult charged = processor.charge(new PaymentProcessor.ChargeRequest(
                payment.getProcessorReference(), 100_000, "INR", "tok_success"));
        assertThat(paymentStatus(payment.getId())).isEqualTo("PENDING");

        retryJob.runOnce();

        Payment recovered = payments.findById(payment.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recovered.getProviderPaymentId()).isEqualTo(charged.providerPaymentId());
        assertThat(count("SELECT count(*) FROM ledger_entries WHERE payment_id = ?", payment.getId())).isEqualTo(2);
    }

    @Test
    void retryJobIgnoresPaymentsThatAreNotDue() throws Exception {
        Fixture f = fixture("tok_timeout_always");
        String id = body(pay(f, "k", 100)).get("id").asString();
        jdbc.update("UPDATE payments SET next_retry_at = now() + interval '1 hour' WHERE id = ?", id);

        assertThat(retryJob.runOnce()).isZero();
        assertThat(payments.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
    }
}
