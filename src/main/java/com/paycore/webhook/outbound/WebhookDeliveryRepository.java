package com.paycore.webhook.outbound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO webhook_deliveries (merchant_id, event_id, event_type, payload, status, attempt_count,
                                            next_attempt_at, created_at, updated_at)
            VALUES (:merchantId, :eventId, :eventType, :payload, 'PENDING', 0, :now, :now, :now)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("merchantId") String merchantId, @Param("eventId") String eventId,
                  @Param("eventType") String eventType, @Param("payload") String payload, @Param("now") Instant now);

    @Query(value = "SELECT * FROM webhook_deliveries WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<WebhookDelivery> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    Optional<WebhookDelivery> findByEventId(String eventId);

    List<WebhookDelivery> findByMerchantIdOrderByIdAsc(String merchantId);
}
