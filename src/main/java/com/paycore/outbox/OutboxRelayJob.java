package com.paycore.outbox;

import com.paycore.redis.DistributedLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules the relay. A separate bean on purpose: calling {@link OutboxRelay#relayBatch()} from inside
 * OutboxRelay itself would bypass Spring's proxy and run it without its transaction.
 *
 * <p>Runs on one instance at a time (Redis lock), so batches are published in order.
 */
@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    static final String LOCK = "outbox-relay";
    /** Longer than a worst-case batch (Kafka publish timeout is 15s). */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final OutboxRelay relay;
    private final DistributedLock lock;

    public OutboxRelayJob(OutboxRelay relay, DistributedLock lock) {
        this.relay = relay;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${paycore.jobs.outbox-interval}")
    public void run() {
        lock.runExclusive(LOCK, LOCK_TTL, () -> {
            try {
                relay.relayBatch();
            } catch (RuntimeException e) {
                log.warn("outbox relay failed, events stay queued and will be retried: {}", e.getMessage());
            }
        });
    }
}
