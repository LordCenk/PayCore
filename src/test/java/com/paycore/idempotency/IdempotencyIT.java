package com.paycore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** PAYCORE_DESIGN.md, failure scenario 1: duplicate payment request. */
class IdempotencyIT extends IntegrationTest {

    @Test
    void retryWithSameKeyReturnsTheOriginalResponseAndChargesOnce() throws Exception {
        Fixture f = fixture("tok_success");
        MvcResult first = pay(f, "order-123", 100_000).andExpect(status().isCreated()).andReturn();

        MvcResult replay = pay(f, "order-123", 100_000)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(body(replay)).isEqualTo(body(first));
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        Fixture f = fixture("tok_success");
        pay(f, "order-123", 100_000).andExpect(status().isCreated());

        pay(f, "order-123", 999)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerMerchant() throws Exception {
        pay(fixture("tok_success"), "shared-key", 100).andExpect(status().isCreated());
        pay(fixture("tok_success"), "shared-key", 100).andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(2);
    }

    @Test
    void concurrentDuplicatesCreateExactlyOnePayment() throws Exception {
        Fixture f = fixture("tok_success");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Integer> call = () -> {
                start.await();
                return pay(f, "double-click", 100_000).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> r : results) {
            statuses.add(r.get());
        }
        pool.shutdown();

        // One winner; the rest either see "in progress" (409) or, if they arrive after it finished, the replay (201).
        assertThat(statuses).allMatch(s -> s == 201 || s == 409);
        assertThat(statuses).contains(201);
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_entries")).isEqualTo(2);
    }

    @Test
    void failedValidationReleasesTheKeySoACorrectedRequestCanUseIt() throws Exception {
        Fixture f = fixture("tok_success");
        Fixture wrongCustomer = new Fixture(f.merchant(), "cust_missing", f.paymentMethodId());

        pay(wrongCustomer, "order-9", 100).andExpect(status().isNotFound());
        pay(f, "order-9", 100).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void crashedRequestIsResumedFromTheStoredPayment() throws Exception {
        Fixture f = fixture("tok_success");
        String id = body(pay(f, "order-5", 100)).get("id").asString();
        // Simulate a crash after the payment was created but before the response was stored.
        jdbc.update("UPDATE idempotency_keys SET status = 'IN_PROGRESS', response_body = NULL, "
                + "updated_at = now() - interval '10 minutes'");

        pay(f, "order-5", 100)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }
}
