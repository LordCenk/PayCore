package com.paycore.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.paycore.outbox.OutboxRelay;
import com.paycore.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

/**
 * Outbox -> Kafka -> consumers, against an in-process Kafka broker.
 * One partition, so a later "sentinel" event proves that earlier events were already consumed.
 */
@EmbeddedKafka(partitions = 1, topics = {KafkaEventsIT.TOPIC, KafkaEventsIT.TOPIC + ".DLT"})
@TestPropertySource(properties = {
        "paycore.events.publisher=kafka",
        "paycore.events.topic=" + KafkaEventsIT.TOPIC,
        "paycore.events.partitions=1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
class KafkaEventsIT extends IntegrationTest {

    static final String TOPIC = "paycore.events.it";

    @Autowired
    OutboxRelay relay;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    EmbeddedKafkaBroker broker;

    private static final Duration WAIT = Duration.ofSeconds(30);

    private long notifications(String template) {
        return count("SELECT count(*) FROM notifications WHERE template = ?", template);
    }

    @Test
    void paymentAndRefundEventsReachEveryConsumerGroup() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100_000);
        refund(f, paymentId, "r", "{}");
        payId(fixture("tok_decline"), 500);

        assertThat(relay.relayBatch()).isEqualTo(6);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(notifications("PAYMENT_RECEIPT")).isEqualTo(1);
            assertThat(notifications("REFUND_CONFIRMATION")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM processed_events WHERE consumer = 'analytics'")).isEqualTo(3);
        });
        mvc.perform(get("/api/v1/analytics/daily").header("Authorization", f.merchant().auth()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].paymentsSucceeded").value(1))
                .andExpect(jsonPath("$[0].amountSucceeded").value(100_000))
                .andExpect(jsonPath("$[0].refundsSucceeded").value(1))
                .andExpect(jsonPath("$[0].amountRefunded").value(100_000))
                .andExpect(jsonPath("$[0].paymentsFailed").value(0));
        mvc.perform(get("/api/v1/customers/" + f.customerId() + "/notifications")
                        .header("Authorization", f.merchant().auth()))
                .andExpect(jsonPath("$[*].template").value(
                        org.hamcrest.Matchers.contains("PAYMENT_RECEIPT", "REFUND_CONFIRMATION")))
                .andExpect(jsonPath("$[0].recipient").value("asha@example.com"));
    }

    @Test
    void eventsForAPaymentAndItsRefundShareTheSameKey() throws Exception {
        Fixture f = fixture("tok_success");
        String paymentId = payId(f, 100);
        refund(f, paymentId, "r", "{}");
        relay.relayBatch();

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker.getBrokersAsString(), "key-check-" + paymentId);
        try (Consumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            List<ConsumerRecord<String, String>> mine = new ArrayList<>();
            await().atMost(WAIT).until(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500)).forEach(r -> {
                    if (paymentId.equals(r.key())) {
                        mine.add(r);
                    }
                });
                return mine.size() >= 4;
            });
            assertThat(mine).extracting(r -> new String(r.headers().lastHeader("eventType").value(),
                            StandardCharsets.UTF_8))
                    .containsExactly("PaymentCreated", "PaymentSucceeded", "RefundRequested", "RefundSucceeded");
        }
    }

    @Test
    void redeliveredEventsAreProcessedOnce() throws Exception {
        Fixture f = fixture("tok_success");
        payId(f, 100);
        relay.relayBatch();
        await().atMost(WAIT).until(() -> notifications("PAYMENT_RECEIPT") == 1);

        // The relay crashed after Kafka acknowledged but before marking the rows published: they go out again.
        jdbc.update("UPDATE outbox_events SET published_at = NULL");
        relay.relayBatch();
        // A later event on the same partition: once it's consumed, the duplicates before it were too.
        payId(fixture("tok_success"), 100);
        relay.relayBatch();
        await().atMost(WAIT).until(() -> notifications("PAYMENT_RECEIPT") == 2);

        assertThat(count("SELECT payments_succeeded FROM merchant_daily_stats WHERE merchant_id = ?",
                f.merchant().id())).isEqualTo(1);
    }

    @Test
    void malformedMessageIsDeadLetteredAndDoesNotBlockThePartition() throws Exception {
        kafka.send(TOPIC, "poison", "this is not json").get();
        payId(fixture("tok_success"), 100);
        relay.relayBatch();

        await().atMost(WAIT).until(() -> notifications("PAYMENT_RECEIPT") == 1);

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker.getBrokersAsString(), "dlt-check");
        try (Consumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            List<ConsumerRecord<String, String>> dead = new ArrayList<>();
            await().atMost(WAIT).until(() -> {
                KafkaTestUtils.getRecords(consumer, Duration.ofMillis(500)).forEach(dead::add);
                return dead.stream().filter(r -> "poison".equals(r.key())).count() >= 1;
            });
            ConsumerRecord<String, String> poison = dead.stream().filter(r -> "poison".equals(r.key())).findFirst()
                    .orElseThrow();
            assertThat(poison.value()).isEqualTo("this is not json");
            assertThat(poison.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        }
    }
}
