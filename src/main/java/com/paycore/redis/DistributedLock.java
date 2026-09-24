package com.paycore.redis;

import com.paycore.common.Ids;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * A Redis lock ({@code SET key token NX PX ttl}) so a job runs on one instance at a time.
 *
 * <p>Release only deletes the key if it still holds our token, so an instance whose lock expired can't
 * delete the next holder's lock. The TTL must exceed the job's worst-case run time. There is no fencing
 * token, so this is for efficiency and ordering, never for correctness: every job it guards is already
 * safe to run concurrently thanks to row locks and idempotent writes.
 */
@Component
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;

    public DistributedLock(StringRedisTemplate redis, RedisGuard guard) {
        this.redis = redis;
        this.guard = guard;
    }

    public static String key(String name) {
        return "paycore:lock:" + name;
    }

    /**
     * Runs {@code task} if this instance gets the lock. If Redis is unavailable the task runs anyway
     * (fail open), because it is safe to run concurrently. Returns whether the task ran.
     */
    public boolean runExclusive(String name, Duration ttl, Runnable task) {
        String token = Ids.random(24);
        Boolean acquired = guard.call("lock " + name,
                () -> redis.opsForValue().setIfAbsent(key(name), token, ttl), null);
        if (acquired == null) {
            log.debug("lock {} unavailable (Redis down); running without it", name);
            task.run();
            return true;
        }
        if (!acquired) {
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            guard.run("unlock " + name, () -> redis.execute(RELEASE, List.of(key(name)), token));
        }
    }
}
