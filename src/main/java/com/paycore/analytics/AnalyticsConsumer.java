package com.paycore.analytics;

import com.paycore.events.DomainEvent;
import com.paycore.events.ProcessedEvents;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Builds per-merchant daily totals from payment events. Its own consumer group, so it reads every event. */
@Component
@ConditionalOnProperty(prefix = "paycore.events", name = "publisher", havingValue = "kafka")
public class AnalyticsConsumer {

    static final String CONSUMER = "analytics";

    private final ProcessedEvents processed;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public AnalyticsConsumer(ProcessedEvents processed, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                             JsonMapper json) {
        this.processed = processed;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
    }

    @KafkaListener(id = "paycore-analytics", topics = "${paycore.events.topic}", groupId = "paycore-analytics")
    public void onEvent(String payload) {
        DomainEvent event = DomainEvent.parse(json, payload);
        String column = switch (event.type()) {
            case "PaymentSucceeded" -> "succeeded";
            case "PaymentFailed" -> "failed";
            case "RefundSucceeded" -> "refunded";
            default -> null;
        };
        if (column == null) {
            return;
        }
        String merchantId = event.dataText("merchantId");
        String currency = event.dataText("currency");
        long amount = event.dataLong("amount");
        Date day = Date.valueOf(LocalDate.ofInstant(event.occurredAt(), ZoneOffset.UTC));

        tx.executeWithoutResult(s -> {
            if (!processed.claim(CONSUMER, event.eventId())) {
                return; // duplicate delivery: already counted
            }
            String increment = switch (column) {
                case "succeeded" -> "payments_succeeded = s.payments_succeeded + 1, "
                        + "amount_succeeded = s.amount_succeeded + EXCLUDED.amount_succeeded";
                case "failed" -> "payments_failed = s.payments_failed + 1";
                default -> "refunds_succeeded = s.refunds_succeeded + 1, "
                        + "amount_refunded = s.amount_refunded + EXCLUDED.amount_refunded";
            };
            jdbc.update("""
                    INSERT INTO merchant_daily_stats AS s (merchant_id, day, currency, payments_succeeded,
                        amount_succeeded, payments_failed, refunds_succeeded, amount_refunded)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (merchant_id, day, currency) DO UPDATE SET\s""" + increment,
                    merchantId, day, currency,
                    column.equals("succeeded") ? 1 : 0, column.equals("succeeded") ? amount : 0,
                    column.equals("failed") ? 1 : 0,
                    column.equals("refunded") ? 1 : 0, column.equals("refunded") ? amount : 0);
        });
    }
}
