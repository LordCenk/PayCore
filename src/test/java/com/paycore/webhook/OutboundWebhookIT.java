package com.paycore.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.paycore.common.Hashing;
import com.paycore.outbox.OutboxRelay;
import com.paycore.support.IntegrationTest;
import com.paycore.webhook.outbound.WebhookDeliveryService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Events flow outbox -> relay -> signed HTTP POST to the merchant's webhook URL. */
class OutboundWebhookIT extends IntegrationTest {

    record Received(String eventId, String eventType, String signature, String body) {}

    @Autowired
    OutboxRelay relay;

    @Autowired
    WebhookDeliveryService deliveries;

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger responseCode = new AtomicInteger(200);

    @BeforeEach
    void startMerchantServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestHeaders().getFirst("X-PayCore-Event-Id"),
                    exchange.getRequestHeaders().getFirst("X-PayCore-Event-Type"),
                    exchange.getRequestHeaders().getFirst("X-PayCore-Signature"), body));
            exchange.sendResponseHeaders(responseCode.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopMerchantServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks";
    }

    @Test
    void paymentEventsAreDeliveredSignedAndInOrder() throws Exception {
        Fixture f = fixture("tok_success", url());
        payId(f, 100);

        assertThat(relay.relayBatch()).isEqualTo(2);
        assertThat(deliveries.deliverDue()).isEqualTo(2);

        assertThat(received).extracting(Received::eventType).containsExactly("PaymentCreated", "PaymentSucceeded");
        for (Received r : received) {
            Map<String, String> parts = parseSignature(r.signature());
            assertThat(parts.get("v1"))
                    .isEqualTo(Hashing.hmacSha256Hex(f.merchant().webhookSecret(), parts.get("t") + "." + r.body()));
            assertThat(r.body()).contains(r.eventId());
        }
        assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status = 'DELIVERED'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
    }

    @Test
    void relayingTwiceDoesNotDuplicateDeliveries() throws Exception {
        Fixture f = fixture("tok_success", url());
        payId(f, 100);
        relay.relayBatch();
        jdbc.update("UPDATE outbox_events SET published_at = NULL"); // simulate a crash before marking published
        relay.relayBatch();

        assertThat(count("SELECT count(*) FROM webhook_deliveries")).isEqualTo(2);
    }

    @Test
    void failingEndpointIsRetriedThenGivenUp() throws Exception {
        responseCode.set(500);
        Fixture f = fixture("tok_decline", url());
        payId(f, 100);
        relay.relayBatch();

        for (int attempt = 1; attempt <= 3; attempt++) {
            jdbc.update("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE status = 'PENDING'");
            deliveries.deliverDue();
        }

        assertThat(count("SELECT count(*) FROM webhook_deliveries WHERE status = 'FAILED' AND attempt_count = 3 "
                + "AND last_response_code = 500")).isEqualTo(2);
        assertThat(received).hasSize(6);
    }

    @Test
    void merchantsWithoutAWebhookUrlGetNoDeliveries() throws Exception {
        payId(fixture("tok_success"), 100);
        relay.relayBatch();
        assertThat(count("SELECT count(*) FROM webhook_deliveries")).isZero();
    }

    private static Map<String, String> parseSignature(String header) {
        String[] parts = header.split(",");
        return Map.of(parts[0].split("=")[0], parts[0].split("=")[1], parts[1].split("=")[0], parts[1].split("=")[1]);
    }
}
