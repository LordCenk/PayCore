package com.paycore.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    /**
     * {@code SELECT ... FOR UPDATE}: every state change takes this row lock first, so two writers
     * (API, retry job, webhook, reconciliation) on any number of instances are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") String id);

    Optional<Payment> findByIdAndMerchantId(String id, String merchantId);

    Optional<Payment> findByProcessorReference(String processorReference);

    List<Payment> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable page);

    List<Payment> findByMerchantIdAndStatusOrderByCreatedAtDesc(String merchantId, PaymentStatus status,
                                                                Pageable page);

    long countByCustomerIdAndCreatedAtAfter(String customerId, Instant since);

    long countByCustomerIdAndStatus(String customerId, PaymentStatus status);

    @Query("select coalesce(avg(p.amount), 0) from Payment p where p.customerId = :customerId and p.status = :status")
    double averageAmount(@Param("customerId") String customerId, @Param("status") PaymentStatus status);

    @Query(value = "SELECT id FROM payments WHERE status = 'PENDING' AND next_retry_at <= :now "
            + "ORDER BY next_retry_at LIMIT :limit", nativeQuery = true)
    List<String> findDueForRetry(@Param("now") Instant now, @Param("limit") int limit);

    List<Payment> findByStatusInAndUpdatedAtAfter(Collection<PaymentStatus> statuses, Instant since);
}
