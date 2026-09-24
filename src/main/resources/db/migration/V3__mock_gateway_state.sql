-- State of the *simulated* payment gateway (MockPaymentProcessor), not PayCore data.
-- A real gateway remembers charges across PayCore restarts; persisting the mock's records lets
-- reconciliation and crash recovery behave the same way after a real restart.
CREATE TABLE mock_gateway_charges (
    reference     VARCHAR(60)  PRIMARY KEY,
    outcome       VARCHAR(20)  NOT NULL,
    provider_id   VARCHAR(100),
    decline_code  VARCHAR(50),
    message       VARCHAR(200),
    token         VARCHAR(100) NOT NULL,
    amount        BIGINT       NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE mock_gateway_refunds (
    reference        VARCHAR(60)  PRIMARY KEY,
    charge_reference VARCHAR(60)  NOT NULL,
    outcome          VARCHAR(20)  NOT NULL,
    provider_id      VARCHAR(100),
    decline_code     VARCHAR(50),
    message          VARCHAR(200),
    amount           BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
