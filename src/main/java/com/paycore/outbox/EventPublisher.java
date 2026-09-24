package com.paycore.outbox;

/** Where outbox events go. A Kafka implementation replaces {@link LoggingEventPublisher} in a later milestone. */
public interface EventPublisher {

    void publish(OutboxEvent event);
}
