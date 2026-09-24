package com.paycore.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Outbox backlog, read from the database at scrape time. The oldest unpublished event's age is the
 * number to alert on: it grows when Kafka is down or the relay is stuck.
 */
@Component
public class OutboxMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    public OutboxMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("paycore.outbox.pending", this, OutboxMetrics::pending)
                .description("Outbox events not yet published")
                .register(registry);
        Gauge.builder("paycore.outbox.oldest.age", this, OutboxMetrics::oldestAgeSeconds)
                .description("Age of the oldest unpublished outbox event")
                .baseUnit("seconds")
                .register(registry);
    }

    double pending() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    double oldestAgeSeconds() {
        Double age = jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) "
                + "FROM outbox_events WHERE published_at IS NULL", Double.class);
        return age == null ? 0 : age;
    }
}
