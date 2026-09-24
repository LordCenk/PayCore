package com.paycore.merchant;

import com.paycore.auth.AuthenticatedMerchant;
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
 * API key hash -> merchant snapshot, so authenticating a request doesn't hit PostgreSQL every time.
 * Entries expire after {@code merchant-cache-ttl}; a status change (e.g. suspension) must call
 * {@link #evict} or it takes effect only after that TTL.
 */
@Component
public class MerchantAuthCache {

    private static final Logger log = LoggerFactory.getLogger(MerchantAuthCache.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final JsonMapper json;
    private final Duration ttl;

    public MerchantAuthCache(StringRedisTemplate redis, RedisGuard guard, JsonMapper json,
                             PayCoreProperties properties) {
        this.redis = redis;
        this.guard = guard;
        this.json = json;
        this.ttl = properties.redis().merchantCacheTtl();
    }

    public static String key(String apiKeyHash) {
        return "paycore:merchant-auth:" + apiKeyHash;
    }

    public Optional<AuthenticatedMerchant> get(String apiKeyHash) {
        String cached = guard.call("merchant cache read", () -> redis.opsForValue().get(key(apiKeyHash)), null);
        if (cached == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(cached, AuthenticatedMerchant.class));
        } catch (JacksonException e) {
            // Corrupt, or written by a different version of this class: treat as a miss and drop it.
            log.warn("dropping unreadable merchant cache entry: {}", e.getOriginalMessage());
            evict(apiKeyHash);
            return Optional.empty();
        }
    }

    public void put(String apiKeyHash, AuthenticatedMerchant merchant) {
        guard.run("merchant cache write",
                () -> redis.opsForValue().set(key(apiKeyHash), json.writeValueAsString(merchant), ttl));
    }

    public void evict(String apiKeyHash) {
        guard.run("merchant cache evict", () -> redis.delete(key(apiKeyHash)));
    }
}
