package com.paycore.notification;

import com.paycore.customer.Customer;
import com.paycore.customer.CustomerRepository;
import com.paycore.events.DomainEvent;
import com.paycore.events.ProcessedEvents;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends customers a receipt for successful payments and refunds. Sending is simulated (logged and stored).
 * Independent consumer group: if it falls behind or fails, payments and analytics are unaffected.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.events", name = "publisher", havingValue = "kafka")
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    static final String CONSUMER = "notifications";

    private final ProcessedEvents processed;
    private final CustomerRepository customers;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public NotificationConsumer(ProcessedEvents processed, CustomerRepository customers, JdbcTemplate jdbc,
                                PlatformTransactionManager txManager, JsonMapper json, Clock clock) {
        this.processed = processed;
        this.customers = customers;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.clock = clock;
    }

    @KafkaListener(id = "paycore-notifications", topics = "${paycore.events.topic}", groupId = "paycore-notifications")
    public void onEvent(String payload) {
        DomainEvent event = DomainEvent.parse(json, payload);
        String template = switch (event.type()) {
            case "PaymentSucceeded" -> "PAYMENT_RECEIPT";
            case "RefundSucceeded" -> "REFUND_CONFIRMATION";
            default -> null;
        };
        if (template == null) {
            return;
        }
        String customerId = event.dataText("customerId");
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new IllegalStateException("Unknown customer " + customerId));

        tx.executeWithoutResult(s -> {
            if (!processed.claim(CONSUMER, event.eventId())) {
                return;
            }
            jdbc.update("INSERT INTO notifications (event_id, merchant_id, customer_id, channel, recipient, template, "
                            + "payload, created_at) VALUES (?, ?, ?, 'EMAIL', ?, ?, ?, ?)",
                    event.eventId(), event.dataText("merchantId"), customerId, customer.getEmail(), template,
                    event.data().toString(), Timestamp.from(clock.instant()));
            log.info("notification sent template={} to={} event={}", template, customer.getEmail(), event.eventId());
        });
    }
}
