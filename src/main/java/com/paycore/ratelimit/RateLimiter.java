package com.paycore.ratelimit;

import com.paycore.config.PayCoreProperties;
import com.paycore.redis.RedisGuard;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket per merchant, in Redis, so the limit holds across every PayCore instance.
 * The whole read-refill-take step is one Lua script, which Redis runs atomically: two instances can't
 * both take the last token. The script uses Redis's clock, so instance clock skew doesn't matter.
 */
@Component
public class RateLimiter {

    private static final DefaultRedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate / 1000)
            local allowed = 0
            local retry_ms = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry_ms = math.ceil((1 - tokens) * 1000 / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)
            return {allowed, math.floor(tokens), retry_ms}
            """, List.class);

    public record Decision(boolean allowed, long remaining, long retryAfterMillis) {}

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final PayCoreProperties.RateLimit config;

    public RateLimiter(StringRedisTemplate redis, RedisGuard guard, PayCoreProperties properties) {
        this.redis = redis;
        this.guard = guard;
        this.config = properties.rateLimit();
    }

    public static String key(String merchantId) {
        return "paycore:ratelimit:" + merchantId;
    }

    public int capacity() {
        return config.capacity();
    }

    /** Fails open: if Redis is down the request is allowed (remaining = -1, i.e. unknown). */
    public Decision tryAcquire(String merchantId) {
        if (!config.enabled()) {
            return new Decision(true, -1, 0);
        }
        List<?> result = guard.call("rate limit", () -> redis.execute(TOKEN_BUCKET, List.of(key(merchantId)),
                String.valueOf(config.capacity()), String.valueOf(config.refillPerSecond())), null);
        if (result == null) {
            return new Decision(true, -1, 0);
        }
        return new Decision(((Number) result.get(0)).longValue() == 1, ((Number) result.get(1)).longValue(),
                ((Number) result.get(2)).longValue());
    }
}
