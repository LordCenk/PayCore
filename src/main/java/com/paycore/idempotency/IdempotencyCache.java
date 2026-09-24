package com.paycore.idempotency;

import com.paycore.config.PayCoreProperties;
import com.paycore.redis.RedisGuard;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fast path for replays: completed responses are copied to Redis so a client retry is answered without
 * touching PostgreSQL. Only COMPLETED keys are cached; claiming a new key always goes to the database,
 * whose unique constraint remains the single source of truth.
 */
@Component
public class IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);

    public record Completed(String requestHash, int status, String body) {}

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final JsonMapper json;
    private final Duration ttl;

    public IdempotencyCache(StringRedisTemplate redis, RedisGuard guard, JsonMapper json,
                            PayCoreProperties properties) {
        this.redis = redis;
        this.guard = guard;
        this.json = json;
        this.ttl = properties.idempotency().ttl();
    }

    public static String key(String merchantId, String idempotencyKey) {
        return "paycore:idempotency:" + merchantId + ":" + idempotencyKey;
    }

    public Optional<Completed> get(String merchantId, String idempotencyKey) {
        String cached = guard.call("idempotency cache read",
                () -> redis.opsForValue().get(key(merchantId, idempotencyKey)), null);
        if (cached == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(cached, Completed.class));
        } catch (JacksonException e) {
            // Unreadable entry: fall back to PostgreSQL, which holds the same response.
            log.warn("dropping unreadable idempotency cache entry: {}", e.getOriginalMessage());
            guard.run("idempotency cache evict", () -> redis.delete(key(merchantId, idempotencyKey)));
            return Optional.empty();
        }
    }

    public void put(String merchantId, String idempotencyKey, Completed completed) {
        guard.run("idempotency cache write", () -> redis.opsForValue()
                .set(key(merchantId, idempotencyKey), json.writeValueAsString(completed), ttl));
    }
}
