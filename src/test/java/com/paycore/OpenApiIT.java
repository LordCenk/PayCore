package com.paycore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class OpenApiIT extends IntegrationTest {

    @Test
    void apiDocsDescribeTheEndpointsAndAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("PayCore API"))
                .andExpect(jsonPath("$.paths['/api/v1/payments'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/payments/{paymentId}/refund'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/payments'].post.parameters[?(@.name == 'Idempotency-Key')]").exists())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.scheme").value("bearer"));
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
