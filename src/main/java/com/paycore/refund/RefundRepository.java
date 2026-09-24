package com.paycore.refund;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") String id);

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(String paymentId);

    Optional<Refund> findByProcessorReference(String processorReference);

    List<Refund> findByStatusAndUpdatedAtBefore(RefundStatus status, Instant before);

    List<Refund> findByStatus(RefundStatus status);
}
