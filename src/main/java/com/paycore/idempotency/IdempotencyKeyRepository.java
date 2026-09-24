package com.paycore.idempotency;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByMerchantIdAndKey(String merchantId, String key);

    /**
     * Claims a key. The unique constraint on (merchant_id, key) guarantees that of N concurrent
     * requests with the same key exactly one gets 1 back; the others get 0.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (merchant_id, key, request_hash, status, created_at, updated_at, expires_at)
            VALUES (:merchantId, :key, :hash, 'IN_PROGRESS', :now, :now, :expiresAt)
            ON CONFLICT (merchant_id, key) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("merchantId") String merchantId, @Param("key") String key, @Param("hash") String hash,
                  @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    /** Reclaims an expired key, or an abandoned IN_PROGRESS key that never created a resource. */
    @Modifying
    @Query(value = """
            UPDATE idempotency_keys
               SET request_hash = :hash, status = 'IN_PROGRESS', resource_id = NULL, response_status = NULL,
                   response_body = NULL, created_at = :now, updated_at = :now, expires_at = :expiresAt
             WHERE merchant_id = :merchantId AND key = :key
               AND (expires_at < :now
                    OR (status = 'IN_PROGRESS' AND resource_id IS NULL AND updated_at < :abandonedBefore))
            """, nativeQuery = true)
    int tryReclaim(@Param("merchantId") String merchantId, @Param("key") String key, @Param("hash") String hash,
                   @Param("now") Instant now, @Param("expiresAt") Instant expiresAt,
                   @Param("abandonedBefore") Instant abandonedBefore);

    @Modifying
    @Query(value = "UPDATE idempotency_keys SET resource_id = :resourceId, updated_at = :now "
            + "WHERE merchant_id = :merchantId AND key = :key", nativeQuery = true)
    int attachResource(@Param("merchantId") String merchantId, @Param("key") String key,
                       @Param("resourceId") String resourceId, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = :status, "
            + "response_body = :body, updated_at = :now WHERE merchant_id = :merchantId AND key = :key",
            nativeQuery = true)
    int complete(@Param("merchantId") String merchantId, @Param("key") String key, @Param("status") int status,
                 @Param("body") String body, @Param("now") Instant now);

    @Modifying
    @Query(value = "DELETE FROM idempotency_keys WHERE merchant_id = :merchantId AND key = :key "
            + "AND status = 'IN_PROGRESS' AND resource_id IS NULL", nativeQuery = true)
    int releaseIfNoResource(@Param("merchantId") String merchantId, @Param("key") String key);

    @Modifying
    @Query(value = "DELETE FROM idempotency_keys WHERE expires_at < :now", nativeQuery = true)
    int deleteExpired(@Param("now") Instant now);
}
