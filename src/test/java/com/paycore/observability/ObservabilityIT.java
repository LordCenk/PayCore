package com.paycore.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.paycore.outbox.OutboxRelay;
import com.paycore.support.IntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ObservabilityIT extends IntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    OutboxRelay relay;

    @Autowired
    PayCoreMetrics metrics;

    private TransactionTemplate transactions;

    @Autowired
    void setTransactionManager(PlatformTransactionManager txManager) {
        this.transactions = new TransactionTemplate(txManager);
    }

    private double counter(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private long timerCount(String name, String... tags) {
        Timer t = registry.find(name).tags(tags).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void paymentOutcomesAndProcessorCallsAreCounted() throws Exception {
        double success = counter("paycore.payment.transitions", "to", "SUCCESS");
        double declined = counter("paycore.payment.transitions", "to", "FAILED", "failure_code", "CARD_DECLINED");
        long charges = timerCount("paycore.processor.latency", "operation", "charge", "outcome", "succeeded");
        long timeouts = timerCount("paycore.processor.latency", "operation", "charge", "outcome", "timeout");

        payId(fixture("tok_success"), 100);
        payId(fixture("tok_decline"), 100);
        payId(fixture("tok_timeout_always"), 100);

        assertThat(counter("paycore.payment.transitions", "to", "SUCCESS")).isEqualTo(success + 1);
        assertThat(counter("paycore.payment.transitions", "to", "FAILED", "failure_code", "CARD_DECLINED"))
                .isEqualTo(declined + 1);
        assertThat(timerCount("paycore.processor.latency", "operation", "charge", "outcome", "succeeded"))
                .isEqualTo(charges + 1);
        assertThat(timerCount("paycore.processor.latency", "operation", "charge", "outcome", "timeout"))
                .isEqualTo(timeouts + 1);
    }

    @Test
    void refundTransitionsAreCounted() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        double refundPending = counter("paycore.payment.transitions", "to", "REFUND_PENDING");
        double refunded = counter("paycore.payment.transitions", "to", "REFUNDED");

        refund(f, paymentId, "r", "{}");

        assertThat(counter("paycore.payment.transitions", "to", "REFUND_PENDING")).isEqualTo(refundPending + 1);
        assertThat(counter("paycore.payment.transitions", "to", "REFUNDED")).isEqualTo(refunded + 1);
        assertThat(counter("paycore.refund.outcomes", "outcome", "succeeded")).isPositive();
    }

    @Test
    void transitionsInARolledBackTransactionAreNotCounted() {
        double before = counter("paycore.payment.transitions", "to", "TEST_STATE");

        transactions.executeWithoutResult(status -> {
            metrics.paymentTransition("TEST_STATE", null);
            status.setRollbackOnly();
        });
        assertThat(counter("paycore.payment.transitions", "to", "TEST_STATE")).isEqualTo(before);

        transactions.executeWithoutResult(status -> metrics.paymentTransition("TEST_STATE", null));
        assertThat(counter("paycore.payment.transitions", "to", "TEST_STATE")).isEqualTo(before + 1);
    }

    @Test
    void outboxGaugesTrackTheBacklog() throws Exception {
        Gauge pending = registry.find("paycore.outbox.pending").gauge();
        Gauge oldest = registry.find("paycore.outbox.oldest.age").gauge();
        assertThat(pending.value()).isZero();
        assertThat(oldest.value()).isZero();

        payId(fixture("tok_success"), 100);
        assertThat(pending.value()).isEqualTo(2);
        assertThat(oldest.value()).isGreaterThanOrEqualTo(0);

        relay.relayBatch();
        assertThat(pending.value()).isZero();
    }

    @Test
    void idempotentReplaysAreCountedBySource() throws Exception {
        Fixture f = fixture("tok_success");
        double fromRedis = counter("paycore.idempotency.replays", "source", "redis");
        pay(f, "k", 100);
        pay(f, "k", 100);
        assertThat(counter("paycore.idempotency.replays", "source", "redis")).isEqualTo(fromRedis + 1);
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        TestMerchant m = merchant();
        mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth()))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.startsWith("req_")));
        mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth())
                        .header("X-Request-Id", "client-trace-42"))
                .andExpect(header().string("X-Request-Id", "client-trace-42"));
        mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth())
                        .header("X-Request-Id", "bad id\nwith newline"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.startsWith("req_")));
    }
}
