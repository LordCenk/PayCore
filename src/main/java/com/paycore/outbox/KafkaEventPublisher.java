package com.paycore.outbox;

import com.paycore.config.PayCoreProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes to one topic, keyed by payment id so each payment's events land on one partition in order.
 * Sends the whole batch, then waits for every acknowledgement ({@code acks=all}, idempotent producer).
 */
@Component
@ConditionalOnProperty(prefix = "paycore.events", name = "publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String EVENT_TYPE_HEADER = "eventType";

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration timeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, PayCoreProperties properties) {
        this.kafka = kafka;
        this.topic = properties.events().topic();
        this.timeout = properties.events().publishTimeout();
    }

    @Override
    public void publishAll(List<OutboxEvent> events) {
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, event.getPartitionKey(), event.getPayload());
            record.headers().add(EVENT_ID_HEADER, event.getEventId().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8));
            sends.add(kafka.send(record));
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishException("Kafka did not acknowledge the batch", e);
        }
    }

    public static class EventPublishException extends RuntimeException {
        EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
