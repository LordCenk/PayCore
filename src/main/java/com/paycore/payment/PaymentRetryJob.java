package com.paycore.payment;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves PENDING payments whose outcome is unknown (timeouts, crashes mid-attempt).
 * Safe to run on every instance: {@link PaymentStateService#claimAttempt} takes a row lock and a lease.
 */
@Component
public class PaymentRetryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryJob.class);
    private static final int BATCH_SIZE = 50;
    static final String ACTOR = "system:retry-job";

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final Clock clock;

    public PaymentRetryJob(PaymentRepository payments, PaymentService paymentService, Clock clock) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paycore.jobs.retry-interval}")
    public void scheduledRun() {
        runOnce();
    }

    /** Returns how many payments were examined. */
    public int runOnce() {
        List<String> due = payments.findDueForRetry(clock.instant(), BATCH_SIZE);
        for (String paymentId : due) {
            try {
                paymentService.attempt(paymentId, true, ACTOR);
            } catch (RuntimeException e) {
                log.error("retry failed payment={}", paymentId, e);
            }
        }
        return due.size();
    }
}
