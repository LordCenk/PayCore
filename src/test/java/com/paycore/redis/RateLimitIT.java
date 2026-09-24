package com.paycore.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {"paycore.rate-limit.capacity=3", "paycore.rate-limit.refill-per-second=0.5"})
class RateLimitIT extends IntegrationTest {

    private ResultActions getSelf(TestMerchant m) throws Exception {
        return mvc.perform(get("/api/v1/merchants/" + m.id()).header("Authorization", m.auth()));
    }

    @Test
    void burstAboveCapacityIsRejectedWithRetryAfter() throws Exception {
        TestMerchant m = merchant();

        getSelf(m).andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "2"));
        getSelf(m).andExpect(status().isOk()).andExpect(header().string("X-RateLimit-Remaining", "1"));
        getSelf(m).andExpect(status().isOk()).andExpect(header().string("X-RateLimit-Remaining", "0"));

        getSelf(m).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void limitsArePerMerchant() throws Exception {
        TestMerchant busy = merchant();
        TestMerchant quiet = merchant();
        for (int i = 0; i < 3; i++) {
            getSelf(busy);
        }
        getSelf(busy).andExpect(status().isTooManyRequests());
        getSelf(quiet).andExpect(status().isOk());
    }

    @Test
    void tokensRefillOverTime() throws Exception {
        TestMerchant m = merchant();
        for (int i = 0; i < 3; i++) {
            getSelf(m);
        }
        getSelf(m).andExpect(status().isTooManyRequests());
        Thread.sleep(2_100); // 0.5 tokens/s
        getSelf(m).andExpect(status().isOk());
    }

    @Test
    void publicRoutesAreNotLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"X\",\"email\":\"x" + i + "@demo.com\"}"))
                    .andExpect(status().isCreated());
        }
        assertThat(count("SELECT count(*) FROM merchants")).isEqualTo(5);
    }
}
