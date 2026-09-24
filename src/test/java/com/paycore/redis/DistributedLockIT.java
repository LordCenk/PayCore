package com.paycore.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.paycore.outbox.OutboxRelayJob;
import com.paycore.support.IntegrationTest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DistributedLockIT extends IntegrationTest {

    @Autowired
    DistributedLock lock;

    @Autowired
    OutboxRelayJob outboxRelayJob;

    @Test
    void secondHolderIsTurnedAwayAndLockIsReleasedAfterwards() {
        AtomicInteger runs = new AtomicInteger();
        boolean outer = lock.runExclusive("job", Duration.ofSeconds(10), () -> {
            runs.incrementAndGet();
            boolean inner = lock.runExclusive("job", Duration.ofSeconds(10), runs::incrementAndGet);
            assertThat(inner).isFalse();
        });

        assertThat(outer).isTrue();
        assertThat(runs).hasValue(1);
        assertThat(redis.hasKey(DistributedLock.key("job"))).isFalse();
    }

    @Test
    void releaseDoesNotDeleteSomeoneElsesLock() {
        lock.runExclusive("job", Duration.ofSeconds(10), () ->
                // Our lock expired and another instance took it while we were still running.
                redis.opsForValue().set(DistributedLock.key("job"), "other-instance"));

        assertThat(redis.opsForValue().get(DistributedLock.key("job"))).isEqualTo("other-instance");
    }

    @Test
    void outboxRelaySkipsWhileAnotherInstanceHoldsTheLock() throws Exception {
        payId(fixture("tok_success"), 100);
        redis.opsForValue().set(DistributedLock.key("outbox-relay"), "other-instance", Duration.ofSeconds(30));

        outboxRelayJob.run();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isEqualTo(2);

        redis.delete(DistributedLock.key("outbox-relay"));
        outboxRelayJob.run();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
    }
}
