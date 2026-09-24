package com.paycore.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules the relay. A separate bean on purpose: calling {@link OutboxRelay#relayBatch()} from inside
 * OutboxRelay itself would bypass Spring's proxy and run it without its transaction.
 */
@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final OutboxRelay relay;

    public OutboxRelayJob(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${paycore.jobs.outbox-interval}")
    public void run() {
        try {
            relay.relayBatch();
        } catch (RuntimeException e) {
            log.warn("outbox relay failed, events stay queued and will be retried: {}", e.getMessage());
        }
    }
}
