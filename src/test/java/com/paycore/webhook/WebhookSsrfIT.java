package com.paycore.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.outbox.OutboxRelay;
import com.paycore.support.IntegrationTest;
import com.paycore.webhook.outbound.WebhookDeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Merchants must not be able to make PayCore send requests to internal services (SSRF). */
@TestPropertySource(properties = "paycore.webhooks.allow-private-targets=false")
class WebhookSsrfIT extends IntegrationTest {

    @Autowired
    OutboxRelay relay;

    @Autowired
    WebhookDeliveryService deliveries;

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8080/actuator", "http://localhost/hooks",
            "http://169.254.169.254/latest/meta-data", "http://10.0.0.5/hooks", "http://[::1]/hooks"})
    void internalWebhookUrlsAreRejectedAtRegistration(String url) throws Exception {
        mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Evil\",\"email\":\"evil@demo.com\",\"webhookUrl\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_URL"));
        assertThat(count("SELECT count(*) FROM merchants")).isZero();
    }

    @Test
    void deliveryToAnInternalAddressIsBlockedEvenIfTheUrlChangedLater() throws Exception {
        Fixture f = fixture("tok_success");
        // e.g. the URL's DNS record now points inside the network
        jdbc.update("UPDATE merchants SET webhook_url = 'http://127.0.0.1:1/hooks'");
        payId(f, 100);
        relay.relayBatch();

        deliveries.deliverDue();

        assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE attempt_count = 1 "
                + "AND last_error LIKE 'blocked: host resolves to internal address%'")).isEqualTo(2);
    }
}
