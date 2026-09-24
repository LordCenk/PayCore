package com.paycore.outbox;

import java.util.List;

/**
 * Where outbox events go: {@link KafkaEventPublisher}, or {@link LoggingEventPublisher} when running without a broker.
 * Implementations must return only once every event is durably accepted, and throw otherwise.
 */
public interface EventPublisher {

    void publishAll(List<OutboxEvent> events);
}
