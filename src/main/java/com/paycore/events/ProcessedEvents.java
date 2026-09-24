package com.paycore.events;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent-consumer bookkeeping. Kafka delivers at least once (redeliveries after a rebalance or crash,
 * duplicates from the outbox relay), so each consumer claims an event id in the same transaction as its
 * side effects. At-least-once delivery plus this check gives effectively-once processing.
 */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** True the first time this consumer sees the event; false for a duplicate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String consumer, String eventId) {
        return jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", consumer, eventId, Timestamp.from(clock.instant())) == 1;
    }
}
