package com.paycore.redis;

import com.paycore.config.PayCoreProperties;
import com.paycore.observability.PayCoreMetrics;
import io.lettuce.core.RedisException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Every Redis call goes through here. Redis only speeds PayCore up (caching, rate limiting, locks),
 * so a Redis failure must never fail a payment: the call returns a fallback instead.
 *
 * <p>A tiny circuit breaker: after one failure, Redis is skipped for {@code failure-cooldown}, so an
 * outage costs one timeout rather than one per request.
 */
@Component
public class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final Duration cooldown;
    private final PayCoreMetrics metrics;
    private final Clock clock;
    private final AtomicReference<Instant> skipUntil = new AtomicReference<>(Instant.MIN);

    public RedisGuard(PayCoreProperties properties, PayCoreMetrics metrics, Clock clock) {
        this.cooldown = properties.redis().failureCooldown();
        this.metrics = metrics;
        this.clock = clock;
    }

    public <T> T call(String operation, Supplier<T> redisCall, T fallback) {
        if (!isAvailable()) {
            metrics.redisFallback(operation);
            return fallback;
        }
        try {
            return redisCall.get();
        } catch (DataAccessException | RedisException e) {
            Instant until = clock.instant().plus(cooldown);
            skipUntil.set(until);
            metrics.redisFallback(operation);
            log.warn("Redis unavailable during {} ({}); using fallback until {}", operation, e.getMessage(), until);
            return fallback;
        }
    }

    public void run(String operation, Runnable redisCall) {
        call(operation, () -> {
            redisCall.run();
            return null;
        }, null);
    }

    public boolean isAvailable() {
        return !clock.instant().isBefore(skipUntil.get());
    }
}
