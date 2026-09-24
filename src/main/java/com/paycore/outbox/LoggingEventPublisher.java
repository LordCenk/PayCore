package com.paycore.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** For running PayCore without Kafka ({@code paycore.events.publisher=logging}). */
@Component
@ConditionalOnProperty(prefix = "paycore.events", name = "publisher", havingValue = "logging")
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publishAll(List<OutboxEvent> events) {
        for (OutboxEvent event : events) {
            log.info("event published type={} key={} eventId={}", event.getEventType(), event.getPartitionKey(),
                    event.getEventId());
        }
    }
}
