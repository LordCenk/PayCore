package com.paycore.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** SKIP LOCKED lets several PayCore instances relay in parallel without publishing the same row twice. */
    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT :limit "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit);

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(String aggregateId);
}
