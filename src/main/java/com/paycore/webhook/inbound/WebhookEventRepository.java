package com.paycore.webhook.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /** Returns 0 when (provider, event_id) was already stored: a redelivery. */
    @Modifying
    @Query(value = """
            INSERT INTO webhook_events (provider, event_id, event_type, payload, status, created_at)
            VALUES (:provider, :eventId, :eventType, :payload, 'RECEIVED', :now)
            ON CONFLICT (provider, event_id) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("provider") String provider, @Param("eventId") String eventId,
                  @Param("eventType") String eventType, @Param("payload") String payload, @Param("now") Instant now);

    Optional<WebhookEvent> findByProviderAndEventId(String provider, String eventId);

    List<WebhookEvent> findByStatusAndCreatedAtBefore(WebhookEvent.Status status, Instant before);
}
