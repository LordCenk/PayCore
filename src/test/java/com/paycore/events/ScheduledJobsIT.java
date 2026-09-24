package com.paycore.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.paycore.outbox.OutboxRelayJob;
import com.paycore.payment.PaymentRetryJob;
import com.paycore.reconciliation.ReconciliationJob;
import com.paycore.support.IntegrationTest;
import com.paycore.webhook.outbound.WebhookDeliveryJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Calls each job the way the scheduler does, through its @Scheduled method. Guards against the
 * self-invocation trap, where a scheduled method calls a @Transactional method on the same bean
 * and silently runs without a transaction.
 */
class ScheduledJobsIT extends IntegrationTest {

    @Autowired
    OutboxRelayJob outboxRelayJob;

    @Autowired
    PaymentRetryJob retryJob;

    @Autowired
    ReconciliationJob reconciliationJob;

    @Autowired
    WebhookDeliveryJob webhookDeliveryJob;

    @Test
    void outboxJobMarksEventsPublishedAndQueuesWebhooks() throws Exception {
        payId(fixture("tok_success", "http://127.0.0.1:1/hooks"), 100);

        outboxRelayJob.run();

        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM webhook_deliveries")).isEqualTo(2);
    }

    @Test
    void retryJobResolvesATimedOutPayment() throws Exception {
        String id = body(pay(fixture("tok_timeout_after_charge"), "k", 100)).get("id").asString();

        retryJob.scheduledRun();

        assertThat(paymentStatus(id)).isEqualTo("SUCCESS");
    }

    @Test
    void reconciliationAndWebhookJobsRun() throws Exception {
        payId(fixture("tok_success", "http://127.0.0.1:1/hooks"), 100);
        outboxRelayJob.run();

        reconciliationJob.run();
        webhookDeliveryJob.run(); // endpoint refuses connections: recorded as a failed attempt

        assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE attempt_count = 1")).isEqualTo(2);
    }
}
