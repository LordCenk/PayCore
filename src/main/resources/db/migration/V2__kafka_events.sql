-- Kafka milestone: partition keys for ordering, idempotent consumers, and consumer read models.

-- Events for a payment and its refunds share one partition key (the payment id), so Kafka keeps them in order.
ALTER TABLE outbox_events ADD COLUMN partition_key VARCHAR(40);
UPDATE outbox_events SET partition_key = aggregate_id WHERE aggregate_type = 'PAYMENT';
UPDATE outbox_events o SET partition_key = r.payment_id
  FROM refunds r WHERE o.aggregate_type = 'REFUND' AND r.id = o.aggregate_id;
UPDATE outbox_events SET partition_key = aggregate_id WHERE partition_key IS NULL;
ALTER TABLE outbox_events ALTER COLUMN partition_key SET NOT NULL;

-- Idempotent consumer: Kafka delivers at least once, so each consumer records what it already handled.
CREATE TABLE processed_events (
    consumer     VARCHAR(60) NOT NULL,
    event_id     VARCHAR(40) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- Read model built by the analytics consumer.
CREATE TABLE merchant_daily_stats (
    merchant_id        VARCHAR(40) NOT NULL,
    day                DATE        NOT NULL,
    currency           VARCHAR(3)  NOT NULL,
    payments_succeeded BIGINT      NOT NULL DEFAULT 0,
    amount_succeeded   BIGINT      NOT NULL DEFAULT 0,
    payments_failed    BIGINT      NOT NULL DEFAULT 0,
    refunds_succeeded  BIGINT      NOT NULL DEFAULT 0,
    amount_refunded    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (merchant_id, day, currency)
);

-- Customer notifications produced by the notification consumer (simulated: logged, not sent).
CREATE TABLE notifications (
    id          BIGSERIAL    PRIMARY KEY,
    event_id    VARCHAR(40)  NOT NULL UNIQUE,
    merchant_id VARCHAR(40)  NOT NULL,
    customer_id VARCHAR(40)  NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    recipient   VARCHAR(200) NOT NULL,
    template    VARCHAR(40)  NOT NULL,
    payload     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_notifications_customer ON notifications (customer_id, created_at);
