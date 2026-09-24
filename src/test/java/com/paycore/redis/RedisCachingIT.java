package com.paycore.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.common.Hashing;
import com.paycore.idempotency.IdempotencyCache;
import com.paycore.merchant.MerchantAuthCache;
import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class RedisCachingIT extends IntegrationTest {

    @Test
    void authenticatedMerchantIsCachedWithATtl() throws Exception {
        TestMerchant m = merchant();
        String key = MerchantAuthCache.key(Hashing.sha256Hex(m.apiKey()));
        assertThat(redis.hasKey(key)).isFalse();

        mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth())).andExpect(status().isOk());

        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    }

    @Test
    void cachedIdentityIsServedWithoutTheDatabaseUntilEvicted() throws Exception {
        TestMerchant m = merchant();
        mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth()));
        jdbc.update("UPDATE merchants SET name = 'Renamed' WHERE id = ?", m.id());

        // Served from Redis: the rename isn't visible yet (bounded by the cache TTL).
        mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth()))
                .andExpect(jsonPath("$.name").value("DemoStore"));

        redis.delete(MerchantAuthCache.key(Hashing.sha256Hex(m.apiKey())));
        mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth()))
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void completedIdempotentResponseIsReplayedFromRedis() throws Exception {
        Fixture f = fixture("tok_success");
        String id = body(pay(f, "order-1", 100)).get("id").asString();
        assertThat(redis.hasKey(IdempotencyCache.key(f.merchant().id(), "order-1"))).isTrue();

        // Remove the database row: a replay can now only come from Redis.
        jdbc.update("DELETE FROM idempotency_keys");
        pay(f, "order-1", 100)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        pay(f, "order-1", 999).andExpect(status().isUnprocessableContent());
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    void failedRequestsAreNotCached() throws Exception {
        Fixture f = fixture("tok_success");
        pay(new Fixture(f.merchant(), "cust_missing", f.paymentMethodId()), "order-1", 100)
                .andExpect(status().isNotFound());
        assertThat(redis.hasKey(IdempotencyCache.key(f.merchant().id(), "order-1"))).isFalse();
    }
}
