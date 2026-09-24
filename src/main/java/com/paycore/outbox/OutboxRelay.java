package com.paycore.outbox;

import com.paycore.webhook.outbound.WebhookDeliveryService;
import java.time.Clock;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Scheduled(fixedDelayString = "${paycore.jobs.outbox-interval}")
    public void scheduledRun() {
        relayBatch();
    }

    /** Returns the number of events relayed. */
    @Transactional
    public int relayBatch() {
        List<OutboxEvent> batch = events.lockUnpublished(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            publisher.publish(event);
            webhookDeliveries.enqueue(event);
            event.markPublished(clock.instant());
        }
        return batch.size();
    }
}
