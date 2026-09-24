package com.paycore.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PayCore's business metrics, exported at /actuator/prometheus.
 * Counters for state changes are incremented only after the transaction commits, so a rolled-back
 * change is never counted.
 */
@Component
public class PayCoreMetrics {

    private final MeterRegistry registry;

    public PayCoreMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@code paycore.payment.transitions{to, failure_code}} */
    public void paymentTransition(String to, String failureCode) {
        afterCommit(() -> Counter.builder("paycore.payment.transitions")
                .description("Payment status changes")
                .tag("to", to)
                .tag("failure_code", failureCode == null ? "none" : failureCode)
                .register(registry)
                .increment());
    }

    /** {@code paycore.refund.outcomes{outcome}} */
    public void refundOutcome(String outcome) {
        afterCommit(() -> registry.counter("paycore.refund.outcomes", "outcome", outcome).increment());
    }

    /** {@code paycore.idempotency.replays{source=redis|database}} */
    public void idempotentReplay(String source) {
        registry.counter("paycore.idempotency.replays", "source", source).increment();
    }

    public void rateLimited() {
        registry.counter("paycore.ratelimit.rejected").increment();
    }

    /** {@code paycore.webhook.deliveries{result=delivered|failed_attempt|gave_up}} */
    public void webhookDelivery(String result) {
        registry.counter("paycore.webhook.deliveries", "result", result).increment();
    }

    /** {@code paycore.redis.fallbacks{operation}}: Redis calls that failed and fell back to PostgreSQL. */
    public void redisFallback(String operation) {
        registry.counter("paycore.redis.fallbacks", "operation", operation).increment();
    }

    public void reconciliationProblems(int count) {
        registry.counter("paycore.reconciliation.problems").increment(count);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
