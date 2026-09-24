package com.paycore.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.outbox.OutboxRelayJob;
import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Redis is only an optimisation: with it unreachable, every feature still works from PostgreSQL. */
@TestPropertySource(properties = {"spring.data.redis.port=1", "paycore.rate-limit.capacity=1"})
class RedisDownIT extends IntegrationTest {

    @Autowired
    OutboxRelayJob outboxRelayJob;

    @Autowired
    RedisGuard guard;

    @Test
    void paymentsIdempotencyAndJobsWorkWithoutRedis() throws Exception {
        Fixture f = fixture("tok_success");

        // Capacity is 1, but with Redis down the limiter fails open.
        String id = body(pay(f, "order-1", 100).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))).get("id").asString();
        pay(f, "order-1", 100)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(header().doesNotExist("X-RateLimit-Remaining"));

        outboxRelayJob.run(); // runs without the lock
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
        assertThat(guard.isAvailable()).isFalse();
    }
}
