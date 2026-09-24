package com.paycore.idempotency;

import com.paycore.common.ApiException;
import com.paycore.config.PayCoreProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Makes a POST safe to retry. See PAYCORE_DESIGN.md, failure scenario 1.
 * The database's unique constraint, not application code, decides which request wins.
 */
@Service
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyKeyRepository keys;
    private final IdempotencyCache cache;
    private final PayCoreProperties.Idempotency config;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository keys, IdempotencyCache cache, PayCoreProperties properties,
                              Clock clock) {
        this.keys = keys;
        this.cache = cache;
        this.config = properties.idempotency();
        this.clock = clock;
    }

    public sealed interface Outcome {}

    /** First time this key is seen: process the request. */
    public record Proceed() implements Outcome {}

    /** Already completed: send back the stored response unchanged. */
    public record Replay(int status, String body) implements Outcome {}

    /** An earlier attempt created the resource but never stored a response (it crashed): return current state. */
    public record Resume(String resourceId) implements Outcome {}

    /** Committed on its own so concurrent requests see the claim immediately. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome begin(String merchantId, String key, String requestHash) {
        validateKey(key);
        // Fast path: a completed request is answered from Redis without a database round trip.
        var cached = cache.get(merchantId, key);
        if (cached.isPresent()) {
            if (!cached.get().requestHash().equals(requestHash)) {
                throw keyReused();
            }
            return new Replay(cached.get().status(), cached.get().body());
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(config.ttl());
        if (keys.tryInsert(merchantId, key, requestHash, now, expiresAt) == 1) {
            return new Proceed();
        }
        Instant abandonedBefore = now.minus(config.inProgressTimeout());
        if (keys.tryReclaim(merchantId, key, requestHash, now, expiresAt, abandonedBefore) == 1) {
            return new Proceed();
        }
        IdempotencyKey existing = keys.findByMerchantIdAndKey(merchantId, key)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_KEY_IN_PROGRESS",
                        "A request with this Idempotency-Key is being processed; retry shortly"));
        if (!existing.getRequestHash().equals(requestHash)) {
            throw keyReused();
        }
        if (existing.getStatus() == IdempotencyKey.Status.COMPLETED) {
            return new Replay(existing.getResponseStatus(), existing.getResponseBody());
        }
        if (existing.getResourceId() != null && existing.getUpdatedAt().isBefore(abandonedBefore)) {
            return new Resume(existing.getResourceId());
        }
        throw ApiException.conflict("IDEMPOTENCY_KEY_IN_PROGRESS",
                "A request with this Idempotency-Key is being processed; retry shortly");
    }

    /** Called inside the transaction that creates the resource, so the link is atomic with it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachResource(String merchantId, String key, String resourceId) {
        keys.attachResource(merchantId, key, resourceId, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String merchantId, String key, String requestHash, int status, String body) {
        keys.complete(merchantId, key, status, body, clock.instant());
        // Cache only after the database commit, so Redis never knows about a response PostgreSQL doesn't.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.put(merchantId, key, new IdempotencyCache.Completed(requestHash, status, body));
            }
        });
    }

    /** The request failed before creating anything (e.g. validation): let the client retry with the same key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String merchantId, String key) {
        keys.releaseIfNoResource(merchantId, key);
    }

    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public int purgeExpired() {
        return keys.deleteExpired(clock.instant());
    }

    private static ApiException keyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request");
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
    }
}
