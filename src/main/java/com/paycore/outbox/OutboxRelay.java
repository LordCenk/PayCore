package com.paycore.outbox;

import com.paycore.webhook.outbound.WebhookDeliveryService;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes committed outbox events at least once. Consumers de-duplicate by {@code eventId}.
 * Each event also fans out to the merchant's outbound webhook queue in the same transaction.
 */
@Component
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository events;
    private final EventPublisher publisher;
    private final WebhookDeliveryService webhookDeliveries;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository events, EventPublisher publisher,
                       WebhookDeliveryService webhookDeliveries, Clock clock) {
        this.events = events;
        this.publisher = publisher;
        this.webhookDeliveries = webhookDeliveries;
        this.clock = clock;
    }

    /** Returns the number of events relayed. */
    @Transactional
    public int relayBatch() {
        List<OutboxEvent> batch = events.lockUnpublished(BATCH_SIZE);
        if (batch.isEmpty()) {
            return 0;
        }
        // Throws if the broker doesn't acknowledge every event: the transaction rolls back and the
        // whole batch is retried on the next run. Some events may then be published twice, never zero times.
        publisher.publishAll(batch);
        for (OutboxEvent event : batch) {
            webhookDeliveries.enqueue(event);
            event.markPublished(clock.instant());
        }
        return batch.size();
    }
}
