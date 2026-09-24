-- PayCore V1 schema. See PAYCORE_DESIGN.md, section 3.
-- Money is stored as BIGINT in the smallest currency unit (paise, cents).

CREATE TABLE merchants (
    id             VARCHAR(40)  PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    email          VARCHAR(200) NOT NULL UNIQUE,
    api_key_hash   VARCHAR(64)  NOT NULL UNIQUE,
    webhook_url    VARCHAR(500),
    webhook_secret VARCHAR(100) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE customers (
    id          VARCHAR(40)  PRIMARY KEY,
    merchant_id VARCHAR(40)  NOT NULL REFERENCES merchants (id),
    name        VARCHAR(200) NOT NULL,
    email       VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_customers_merchant_email ON customers (merchant_id, email);

CREATE TABLE payment_methods (
    id          VARCHAR(40)  PRIMARY KEY,
    customer_id VARCHAR(40)  NOT NULL REFERENCES customers (id),
    type        VARCHAR(20)  NOT NULL,
    provider    VARCHAR(40)  NOT NULL,
    token       VARCHAR(100) NOT NULL,
    last_four   VARCHAR(4)      NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_payment_methods_customer ON payment_methods (customer_id);

CREATE TABLE payments (
    id                  VARCHAR(40)  PRIMARY KEY,
    merchant_id         VARCHAR(40)  NOT NULL REFERENCES merchants (id),
    customer_id         VARCHAR(40)  NOT NULL REFERENCES customers (id),
    payment_method_id   VARCHAR(40)  NOT NULL REFERENCES payment_methods (id),
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)      NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    provider            VARCHAR(40)  NOT NULL,
    processor_reference VARCHAR(60)  NOT NULL UNIQUE,
    provider_payment_id VARCHAR(100),
    idempotency_key     VARCHAR(255),
    attempt_count       INT          NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ,
    failure_code        VARCHAR(50),
    failure_message     VARCHAR(500),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    UNIQUE (provider, provider_payment_id)
);
CREATE INDEX idx_payments_merchant_created ON payments (merchant_id, created_at);
CREATE INDEX idx_payments_status_updated ON payments (status, updated_at);
CREATE INDEX idx_payments_customer_created ON payments (customer_id, created_at);

CREATE TABLE refunds (
    id                  VARCHAR(40)  PRIMARY KEY,
    payment_id          VARCHAR(40)  NOT NULL REFERENCES payments (id),
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    status              VARCHAR(20)  NOT NULL,
    reason              VARCHAR(500),
    processor_reference VARCHAR(60)  NOT NULL UNIQUE,
    provider_refund_id  VARCHAR(100),
    failure_code        VARCHAR(50),
    failure_message     VARCHAR(500),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
-- V1 business rule: at most one active or successful refund per payment.
CREATE UNIQUE INDEX uq_refunds_one_active_per_payment
    ON refunds (payment_id) WHERE status IN ('PENDING', 'SUCCEEDED');

CREATE TABLE ledger_entries (
    id             BIGSERIAL    PRIMARY KEY,
    transaction_id VARCHAR(40)  NOT NULL,
    payment_id     VARCHAR(40)  NOT NULL REFERENCES payments (id),
    refund_id      VARCHAR(40)  REFERENCES refunds (id),
    account_id     VARCHAR(100) NOT NULL,
    entry_type     VARCHAR(10)  NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount         BIGINT       NOT NULL CHECK (amount > 0),
    currency       VARCHAR(3)      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_ledger_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_payment ON ledger_entries (payment_id);

CREATE TABLE idempotency_keys (
    id              BIGSERIAL    PRIMARY KEY,
    merchant_id     VARCHAR(40)  NOT NULL REFERENCES merchants (id),
    key             VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    resource_id     VARCHAR(40),
    status          VARCHAR(20)  NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (merchant_id, key)
);
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);

CREATE TABLE webhook_events (
    id           BIGSERIAL    PRIMARY KEY,
    provider     VARCHAR(40)  NOT NULL,
    event_id     VARCHAR(100) NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    payload      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    error        VARCHAR(500),
    processed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (provider, event_id)
);

CREATE TABLE outbox_events (
    id             BIGSERIAL   PRIMARY KEY,
    event_id       VARCHAR(40) NOT NULL UNIQUE,
    aggregate_type VARCHAR(20) NOT NULL,
    aggregate_id   VARCHAR(40) NOT NULL,
    merchant_id    VARCHAR(40) NOT NULL,
    event_type     VARCHAR(40) NOT NULL,
    payload        TEXT        NOT NULL,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

CREATE TABLE webhook_deliveries (
    id                 BIGSERIAL    PRIMARY KEY,
    merchant_id        VARCHAR(40)  NOT NULL REFERENCES merchants (id),
    event_id           VARCHAR(40)  NOT NULL UNIQUE,
    event_type         VARCHAR(40)  NOT NULL,
    payload            TEXT         NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    attempt_count      INT          NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ  NOT NULL,
    last_response_code INT,
    last_error         VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE audit_logs (
    id          BIGSERIAL    PRIMARY KEY,
    entity_type VARCHAR(20)  NOT NULL,
    entity_id   VARCHAR(40)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    old_value   VARCHAR(40),
    new_value   VARCHAR(40),
    actor       VARCHAR(100) NOT NULL,
    metadata    TEXT,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id, created_at);
