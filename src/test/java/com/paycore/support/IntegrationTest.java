package com.paycore.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full application against a real PostgreSQL database (see application-test.yml).
 * Background jobs are disabled; tests invoke them directly so every step is deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    private int counter;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE audit_logs, webhook_deliveries, outbox_events, webhook_events, idempotency_keys, "
                + "ledger_entries, refunds, payments, payment_methods, customers, merchants, processed_events, "
                + "merchant_daily_stats, notifications RESTART IDENTITY CASCADE");
    }

    public record TestMerchant(String id, String apiKey, String webhookSecret) {
        public String auth() {
            return "Bearer " + apiKey;
        }
    }

    /** A merchant with one customer and one payment method using the given gateway test token. */
    public record Fixture(TestMerchant merchant, String customerId, String paymentMethodId) {}

    protected TestMerchant merchant() throws Exception {
        return merchant(null);
    }

    protected TestMerchant merchant(String webhookUrl) throws Exception {
        String body = webhookUrl == null
                ? "{\"name\":\"DemoStore\",\"email\":\"m" + (++counter) + "-" + System.nanoTime() + "@demo.com\"}"
                : "{\"name\":\"DemoStore\",\"email\":\"m" + (++counter) + "-" + System.nanoTime()
                        + "@demo.com\",\"webhookUrl\":\"" + webhookUrl + "\"}";
        JsonNode r = body(mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()));
        return new TestMerchant(r.get("merchant").get("id").asString(), r.get("apiKey").asString(),
                r.get("webhookSecret").asString());
    }

    protected String customer(TestMerchant m) throws Exception {
        return body(mvc.perform(post("/api/v1/customers").header("Authorization", m.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Asha\",\"email\":\"asha@example.com\"}"))
                .andExpect(status().isCreated())).get("id").asString();
    }

    protected String paymentMethod(TestMerchant m, String customerId, String token) throws Exception {
        return body(mvc.perform(post("/api/v1/payment-methods").header("Authorization", m.auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId + "\",\"type\":\"CARD\",\"token\":\"" + token
                        + "\",\"lastFour\":\"4242\"}"))
                .andExpect(status().isCreated())).get("id").asString();
    }

    protected Fixture fixture(String token) throws Exception {
        return fixture(token, null);
    }

    protected Fixture fixture(String token, String webhookUrl) throws Exception {
        TestMerchant m = merchant(webhookUrl);
        String customerId = customer(m);
        return new Fixture(m, customerId, paymentMethod(m, customerId, token));
    }

    protected MockHttpServletRequestBuilder payRequest(Fixture f, String idempotencyKey, long amount) {
        return post("/api/v1/payments")
                .header("Authorization", f.merchant().auth())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"currency\":\"INR\",\"customerId\":\"" + f.customerId()
                        + "\",\"paymentMethodId\":\"" + f.paymentMethodId() + "\"}");
    }

    protected ResultActions pay(Fixture f, String idempotencyKey, long amount) throws Exception {
        return mvc.perform(payRequest(f, idempotencyKey, amount));
    }

    /** Pays and returns the payment id. */
    protected String payId(Fixture f, long amount) throws Exception {
        return body(pay(f, "key-" + (++counter) + "-" + System.nanoTime(), amount)).get("id").asString();
    }

    protected ResultActions refund(Fixture f, String paymentId, String idempotencyKey, String body) throws Exception {
        return mvc.perform(post("/api/v1/payments/" + paymentId + "/refund")
                .header("Authorization", f.merchant().auth())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return body(result.andReturn());
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected String paymentStatus(String paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
