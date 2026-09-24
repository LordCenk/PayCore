package com.paycore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Client mistakes get a 4xx in PayCore's error format, never a 500. */
class ErrorHandlingIT extends IntegrationTest {

    @Test
    void unknownPathIs404() throws Exception {
        TestMerchant m = merchant();
        mvc.perform(get("/api/v1/nope").header("Authorization", m.auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        TestMerchant m = merchant();
        mvc.perform(delete("/api/v1/payments").header("Authorization", m.auth()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void badQueryParametersAre400() throws Exception {
        TestMerchant m = merchant();
        mvc.perform(get("/api/v1/payments?status=BOGUS").header("Authorization", m.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/v1/analytics/daily?from=yesterday").header("Authorization", m.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        TestMerchant m = merchant();
        mvc.perform(post("/api/v1/customers").header("Authorization", m.auth())
                        .contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void concurrentRegistrationsWithTheSameEmailGet409NotA500() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Race\",\"email\":\"race@demo.com\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> r : results) {
            statuses.add(r.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(count("SELECT count(*) FROM merchants")).isEqualTo(1);
    }
}
