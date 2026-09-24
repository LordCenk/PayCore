# PayCore

A simplified, distributed payment processing system: the kind of payment
infrastructure a fintech backend needs. It is an educational project, so no
real money moves and no real card data is stored.

---

## 1. System Objective

PayCore lets a **merchant** charge its **customers** through a single API and
reliably drive every payment to a final, correct state, even when networks
fail, requests are duplicated, services crash, or the payment processor
answers late or twice.

The central engineering question:

> How do you process payments reliably when networks fail, requests are
> duplicated, services crash, and the same payment request may arrive
> multiple times?

### Scope of V1

A merchant calls:

```http
POST /api/v1/payments
Idempotency-Key: order-123-payment
```

```json
{
  "amount": 100000,
  "currency": "INR",
  "customerId": "cust_123",
  "paymentMethodId": "pm_456"
}
```

PayCore validates the request, records it, sends it to a **mock payment
processor**, and eventually reaches a final state: `SUCCESS` or `FAILED`.
Successful payments can later be refunded.

### Architecture

```text
Client (merchant)
   │
   ▼
Payment API            REST controllers, auth (API key), validation
   │
   ▼
Payment Service        state machine, idempotency, fraud rules, locking
   │
   ▼
Transaction DB         PostgreSQL: payments, refunds, ledger, outbox, audit
   │
   ▼
Message Broker         Kafka (events published via transactional outbox)
   │
   ▼
Payment Processor      mock gateway: 70% success / 20% failure / 10% timeout
   │
   ▼
Webhook Service        inbound provider webhooks + outbound merchant webhooks
```

### Feature list

| Feature                      | Where it lives                                                    |
| ---------------------------- | ----------------------------------------------------------------- |
| Payment creation             | `POST /payments`, Payment Service                                 |
| Transaction lifecycle        | Payment state machine (Section 2)                                 |
| Idempotency keys             | `idempotency_keys` table + unique constraint                      |
| Payment retries              | Retry of processor calls with backoff, same processor reference   |
| Webhooks                     | `webhook_events` (inbound), `webhook_deliveries` (outbound)       |
| Refunds                      | `refunds` table, refund sub-lifecycle                             |
| Transaction reconciliation   | Scheduled job that compares PayCore state with the processor      |
| Fraud-rule simulation        | Rule engine run before the processor call                         |
| Distributed locking          | Row locks in PostgreSQL (V1), Redis lock later                    |
| Audit logs                   | `audit_logs` table, append-only, one row per state change         |

### Build order (milestones)

All of these are implemented.

1. Java 21 + Spring Boot + PostgreSQL: merchants, customers, payments,
   `POST /payments`, state machine, mock processor → SUCCESS / FAILED
2. Idempotency keys
3. Ledger entries + refunds
4. Redis (idempotency cache, distributed lock, rate limiting)
5. Kafka + transactional outbox
6. Webhooks (inbound and outbound)
7. Retries + reconciliation
8. Observability (metrics, structured logs, dashboards, alerts)
9. Delivery: Docker image, OpenAPI docs, CI with an end-to-end smoke test

The mock processor stores its records in `mock_gateway_*` tables. They model the
external gateway's own storage (a real gateway remembers charges across PayCore
restarts); PayCore's code only reaches them through the `PaymentProcessor`
interface.

---

## 2. Payment Lifecycle

### States

| State            | Meaning                                                                 |
| ---------------- | ----------------------------------------------------------------------- |
| `CREATED`        | Request accepted and stored. Processing has not started.                |
| `PENDING`        | Sent to (or about to be sent to) the processor. Outcome not yet known.  |
| `SUCCESS`        | Processor confirmed the charge. Ledger entries written. **Final\***     |
| `FAILED`         | Processor rejected it, fraud rules blocked it, or retries exhausted. **Final** |
| `CANCELLED`      | Merchant cancelled before processing started. **Final**                 |
| `REFUND_PENDING` | A refund was requested and is being processed.                          |
| `REFUNDED`       | Refund completed. **Final**                                             |

\* `SUCCESS` is final for the charge; it can only move forward into the refund
flow.

> `CANCELLED` was added to support `POST /payments/{id}/cancel`. Without it,
> the cancel endpoint has no state to land in.

### State machine

```text
                    ┌─────────────┐
                    │   CREATED   │───────────────┐
                    └──────┬──────┘               │ cancel
                           │                      ▼
                           │               ┌─────────────┐
                           │               │  CANCELLED  │
                           ▼               └─────────────┘
                    ┌─────────────┐
                    │   PENDING   │
                    └──────┬──────┘
                           │
                 ┌─────────┴─────────┐
                 │                   │
                 ▼                   ▼
          ┌─────────────┐      ┌─────────────┐
          │   SUCCESS   │      │   FAILED    │
          └──────┬──────┘      └─────────────┘
                 │   ▲
          refund │   │ refund failed
                 ▼   │
          ┌──────────────────┐
          │  REFUND_PENDING  │
          └────────┬─────────┘
                   │
                   ▼
          ┌─────────────┐
          │  REFUNDED   │
          └─────────────┘
```

### Allowed transitions

| Current          | Allowed next     | Trigger                                              |
| ---------------- | ---------------- | ---------------------------------------------------- |
| `CREATED`        | `PENDING`        | Processing starts (fraud checks passed)              |
| `CREATED`        | `FAILED`         | Fraud rule blocks the payment                        |
| `CREATED`        | `CANCELLED`      | Merchant calls `/cancel`                             |
| `PENDING`        | `SUCCESS`        | Processor (response, webhook, or reconciliation) confirms |
| `PENDING`        | `FAILED`         | Processor declines, or retries exhausted             |
| `SUCCESS`        | `REFUND_PENDING` | Merchant calls `/refund`                             |
| `REFUND_PENDING` | `REFUNDED`       | Processor confirms refund                            |
| `REFUND_PENDING` | `SUCCESS`        | Processor rejects refund (payment is still charged)  |

Every other transition is rejected (e.g. `FAILED → SUCCESS`,
`SUCCESS → PENDING`, `REFUNDED → anything`).

Enforcement rules:

- Transitions live in one place (a `PaymentStatus.canTransitionTo(next)` method
  or a transition table), never as ad-hoc `setStatus()` calls.
- Updates are conditional on the current state so two concurrent writers
  cannot both win:

  ```sql
  UPDATE payments
     SET status = 'SUCCESS', version = version + 1, updated_at = now()
   WHERE id = :id AND status = 'PENDING';
  -- 0 rows updated → someone else already moved it; re-read and decide.
  ```

- A transition into a state the payment is **already in** (for example a
  duplicate "success" webhook) is a no-op, not an error.
- Every transition writes an `audit_logs` row and an `outbox_events` row in
  the **same DB transaction** as the status change.

### Refund rule (V1)

- Only `SUCCESS` payments can be refunded.
- V1 supports **one full refund per payment**. Partial and multiple refunds
  are a later extension (the `refunds` table already allows N rows per
  payment).

### Happy path: pay ₹1,000

1. Client sends `POST /payments` with `Idempotency-Key: order-123-payment`.
2. PayCore authenticates the merchant (API key) and validates: merchant
   active, customer belongs to merchant, payment method belongs to customer
   and is active, amount > 0, currency supported, idempotency key not seen
   before (or seen with the same request → return stored result).
3. Insert payment with `status = CREATED` and a generated
   `processor_reference` (see Failure Scenario 3).
4. Run fraud rules. Blocked → `FAILED` with `failure_code = FRAUD_SUSPECTED`.
5. Move to `PENDING` and commit.
6. Call the mock processor with `processor_reference` as its idempotency key.
7. On success: move to `SUCCESS`, write balanced ledger entries, write outbox
   event `PaymentSucceeded`, all in one DB transaction.
8. Outbox relay publishes `PaymentSucceeded` to Kafka.
9. Consumers: merchant webhook delivery, notification, analytics, audit.

### Fraud-rule simulation (runs before step 5)

| Rule                    | Example condition                                         | Result  |
| ----------------------- | --------------------------------------------------------- | ------- |
| Amount limit            | amount > ₹2,00,000                                        | BLOCK   |
| Velocity                | > 5 payments from the same customer in 1 minute           | BLOCK   |
| Blocked payment method  | payment method token on a deny list                       | BLOCK   |
| Unusual amount          | amount > 10× the customer's average                       | FLAG (allow, audit) |

---

## 3. Database Design

Conventions:

- Primary keys are prefixed string IDs (`pay_…`, `cust_…`) or UUIDs.
- **Money is stored as `BIGINT` in the smallest currency unit** (₹1,000 =
  `100000` paise). Java side uses `long` or `BigDecimal`; never `double`.
- `currency` is an ISO-4217 code (`CHAR(3)`).
- All timestamps are `TIMESTAMPTZ` in UTC.
- Mutable tables have a `version` column for optimistic locking.

### ER diagram

```text
┌──────────────┐
│  merchants   │
└──────┬───────┘
       │ 1:N
       ▼
┌──────────────┐
│  customers   │
└──────┬───────┘
       │ 1:N
       ▼
┌──────────────────┐
│ payment_methods  │
└────────┬─────────┘
         │ 1:N
         ▼
┌──────────────────┐         ┌──────────────────┐
│     payments     │◀────────│ idempotency_keys │
└─┬──────┬──────┬──┘         └──────────────────┘
  │      │      │
  │1:N   │1:N   │1:N
  ▼      ▼      ▼
┌───────┐┌──────────────┐┌────────────┐
│refunds││ledger_entries││ audit_logs │
└───────┘└──────────────┘└────────────┘

Supporting tables (not FK-bound to one payment):
  webhook_events      inbound events from the processor
  webhook_deliveries  outbound events to merchants
  outbox_events       events waiting to be published to Kafka
```

### merchants

| Column       | Type          | Notes                                   |
| ------------ | ------------- | --------------------------------------- |
| id           | VARCHAR PK    | `merchant_001`                          |
| name         | VARCHAR       |                                         |
| email        | VARCHAR       | UNIQUE                                  |
| api_key_hash | VARCHAR       | store a hash, never the raw key         |
| webhook_url  | VARCHAR       | where outbound webhooks are delivered   |
| webhook_secret | VARCHAR     | used to sign outbound webhooks (HMAC)   |
| status       | VARCHAR       | `ACTIVE`, `SUSPENDED`                   |
| created_at   | TIMESTAMPTZ   |                                         |
| updated_at   | TIMESTAMPTZ   |                                         |

### customers

| Column      | Type        | Notes                         |
| ----------- | ----------- | ----------------------------- |
| id          | VARCHAR PK  | `cust_001`                    |
| merchant_id | FK          | → merchants.id                |
| name        | VARCHAR     |                               |
| email       | VARCHAR     |                               |
| created_at  | TIMESTAMPTZ |                               |
| updated_at  | TIMESTAMPTZ |                               |

Index: `(merchant_id, email)`.

### payment_methods

No real card numbers are ever stored. Only a mock token and the last four
digits.

| Column      | Type        | Notes                          |
| ----------- | ----------- | ------------------------------ |
| id          | VARCHAR PK  | `pm_456`                       |
| customer_id | FK          | → customers.id                 |
| type        | VARCHAR     | `CARD`, `UPI`                  |
| provider    | VARCHAR     | `MOCK_VISA`                    |
| token       | VARCHAR     | `tok_test_abc123`              |
| last_four   | CHAR(4)     | `4242`                         |
| status      | VARCHAR     | `ACTIVE`, `DISABLED`           |
| created_at  | TIMESTAMPTZ |                                |

### payments (core table)

| Column              | Type        | Notes                                                    |
| ------------------- | ----------- | -------------------------------------------------------- |
| id                  | VARCHAR PK  | `pay_123`                                                |
| merchant_id         | FK          | → merchants.id                                           |
| customer_id         | FK          | → customers.id                                           |
| payment_method_id   | FK          | → payment_methods.id                                     |
| amount              | BIGINT      | minor units, `CHECK (amount > 0)`                        |
| currency            | CHAR(3)     | `INR`                                                    |
| status              | VARCHAR     | see Section 2                                            |
| provider            | VARCHAR     | `MOCK_GATEWAY`                                           |
| processor_reference | VARCHAR     | **generated by PayCore before calling the processor**; UNIQUE |
| provider_payment_id | VARCHAR     | ID returned by the processor, nullable until known       |
| idempotency_key     | VARCHAR     | copy of the client key, for lookup/debugging             |
| attempt_count       | INT         | number of processor calls made                           |
| next_retry_at       | TIMESTAMPTZ | when the retry worker should try again, nullable         |
| failure_code        | VARCHAR     | `CARD_DECLINED`, `FRAUD_SUSPECTED`, `PROCESSOR_TIMEOUT`  |
| failure_message     | VARCHAR     |                                                          |
| version             | BIGINT      | optimistic lock                                          |
| created_at          | TIMESTAMPTZ |                                                          |
| updated_at          | TIMESTAMPTZ |                                                          |

Indexes: `(merchant_id, created_at)`, `(status, updated_at)` for the
reconciliation job, UNIQUE `(processor_reference)`, UNIQUE
`(provider, provider_payment_id)`.

### refunds

| Column              | Type        | Notes                                   |
| ------------------- | ----------- | --------------------------------------- |
| id                  | VARCHAR PK  | `ref_001`                               |
| payment_id          | FK          | → payments.id                           |
| amount              | BIGINT      | minor units, `≤ payment.amount`         |
| status              | VARCHAR     | `PENDING`, `SUCCEEDED`, `FAILED`        |
| reason              | VARCHAR     |                                         |
| processor_reference | VARCHAR     | UNIQUE, generated before processor call |
| provider_refund_id  | VARCHAR     |                                         |
| created_at          | TIMESTAMPTZ |                                         |
| updated_at          | TIMESTAMPTZ |                                         |

V1 rule "one refund per payment" is enforced with a partial unique index:
`UNIQUE (payment_id) WHERE status IN ('PENDING','SUCCEEDED')`.

### ledger_entries

Double-entry bookkeeping. A payment is not just "money moved"; it is a
transaction that produces balanced accounting entries. Rows are
**append-only**: corrections are new entries, never updates.

| Column         | Type        | Notes                                         |
| -------------- | ----------- | --------------------------------------------- |
| id             | BIGSERIAL PK|                                               |
| transaction_id | VARCHAR     | groups the entries of one money movement      |
| payment_id     | FK          | → payments.id                                 |
| refund_id      | FK          | → refunds.id, nullable                        |
| account_id     | VARCHAR     | `CUSTOMER:cust_001`, `MERCHANT:merchant_001`  |
| entry_type     | VARCHAR     | `DEBIT`, `CREDIT`                             |
| amount         | BIGINT      | minor units, `> 0`                            |
| currency       | CHAR(3)     |                                               |
| created_at     | TIMESTAMPTZ |                                               |

Example: customer pays ₹1,000:

```text
transaction_id  account               type    amount
----------------------------------------------------
TX001           CUSTOMER:cust_001     DEBIT   100000
TX001           MERCHANT:merchant_001 CREDIT  100000
```

Refund of that payment:

```text
TX002           MERCHANT:merchant_001 DEBIT   100000
TX002           CUSTOMER:cust_001     CREDIT  100000
```

**Invariant:** for every `transaction_id`, `SUM(DEBIT) == SUM(CREDIT)`. This is
checked in code before commit and by the reconciliation job.

### idempotency_keys

| Column          | Type        | Notes                                           |
| --------------- | ----------- | ----------------------------------------------- |
| id              | BIGSERIAL PK|                                                 |
| merchant_id     | FK          | → merchants.id                                  |
| key             | VARCHAR     | value of the `Idempotency-Key` header           |
| request_hash    | VARCHAR     | SHA-256 of the normalized request body          |
| resource_id     | VARCHAR     | payment or refund created by the request; set in the same transaction that creates it |
| status          | VARCHAR     | `IN_PROGRESS`, `COMPLETED`                      |
| response_status | INT         | HTTP status returned the first time             |
| response_body   | TEXT        | stored JSON response, replayed on retries       |
| created_at      | TIMESTAMPTZ |                                                 |
| updated_at      | TIMESTAMPTZ | used to detect abandoned `IN_PROGRESS` keys     |
| expires_at      | TIMESTAMPTZ | created_at + 24h                                |

The same table serves payments and refunds, so the column is `resource_id`
rather than `payment_id`. The request hash includes the endpoint path, so a key
reused on a different endpoint is rejected as a different request.

Constraint: **UNIQUE `(merchant_id, key)`**. Keys are scoped per merchant so
two merchants can use the same key string.

### webhook_events (inbound, from the processor)

| Column       | Type        | Notes                                       |
| ------------ | ----------- | ------------------------------------------- |
| id           | BIGSERIAL PK|                                             |
| provider     | VARCHAR     | `MOCK_GATEWAY`                              |
| event_id     | VARCHAR     | provider's unique event ID                  |
| event_type   | VARCHAR     | `payment.succeeded`, `refund.succeeded`, …  |
| payload      | JSONB       | raw body as received                        |
| status       | VARCHAR     | `RECEIVED`, `PROCESSED`, `IGNORED`, `FAILED`|
| error        | VARCHAR     | reason, if FAILED                           |
| processed_at | TIMESTAMPTZ |                                             |
| created_at   | TIMESTAMPTZ |                                             |

Constraint: **UNIQUE `(provider, event_id)`**. Providers retry webhooks.

### webhook_deliveries (outbound, to merchants)

| Column          | Type        | Notes                                     |
| --------------- | ----------- | ----------------------------------------- |
| id              | BIGSERIAL PK|                                           |
| merchant_id     | FK          |                                           |
| event_id        | VARCHAR     | UNIQUE; merchants use it to de-duplicate  |
| event_type      | VARCHAR     | `payment.succeeded`, …                    |
| payload         | JSONB       |                                           |
| status          | VARCHAR     | `PENDING`, `DELIVERED`, `FAILED`          |
| attempt_count   | INT         |                                           |
| next_attempt_at | TIMESTAMPTZ | exponential backoff                       |
| last_response_code | INT      |                                           |
| created_at      | TIMESTAMPTZ |                                           |

### outbox_events (transactional outbox for Kafka)

| Column         | Type        | Notes                                  |
| -------------- | ----------- | -------------------------------------- |
| id             | BIGSERIAL PK|                                        |
| aggregate_type | VARCHAR     | `PAYMENT`, `REFUND`                    |
| event_id       | VARCHAR     | UNIQUE; consumers de-duplicate on it   |
| aggregate_id   | VARCHAR     | `pay_123`                              |
| partition_key  | VARCHAR     | Kafka key: the payment id, also for refund events |
| merchant_id    | VARCHAR     | used to fan out merchant webhooks      |
| event_type     | VARCHAR     | `PaymentSucceeded`, `PaymentFailed`, … |
| payload        | TEXT        | JSON                                   |
| published_at   | TIMESTAMPTZ | null until Kafka acknowledged it       |
| created_at     | TIMESTAMPTZ |                                        |

### processed_events, merchant_daily_stats, notifications (Kafka consumers)

- `processed_events (consumer, event_id)`: primary key. Each consumer inserts
  the event id in the same transaction as its side effect, and skips the event
  if the row already exists (idempotent consumer).
- `merchant_daily_stats`: per merchant, day and currency: payments succeeded
  and failed, amount succeeded, refunds and amount refunded. Built by the
  analytics consumer.
- `notifications`: customer receipts produced by the notification consumer
  (sending is simulated), `event_id` UNIQUE.

### audit_logs

Append-only. The application's DB user has no `UPDATE`/`DELETE` on it.

| Column      | Type        | Notes                                           |
| ----------- | ----------- | ----------------------------------------------- |
| id          | BIGSERIAL PK|                                                 |
| entity_type | VARCHAR     | `PAYMENT`, `REFUND`, `MERCHANT`                 |
| entity_id   | VARCHAR     |                                                 |
| action      | VARCHAR     | `STATUS_CHANGED`, `CREATED`, `FRAUD_FLAGGED`    |
| old_value   | VARCHAR     | e.g. `PENDING`                                  |
| new_value   | VARCHAR     | e.g. `SUCCESS`                                  |
| actor       | VARCHAR     | `merchant:merchant_001`, `system:reconciler`, `webhook:MOCK_GATEWAY` |
| metadata    | JSONB       | request id, failure code, etc.                  |
| created_at  | TIMESTAMPTZ |                                                 |

---

## 4. API Design

Conventions:

- Base path `/api/v1`, JSON bodies, amounts in minor units.
- Merchant auth via `Authorization: Bearer <api_key>`. The merchant is taken
  from the key, never from the request body.
- All `POST` endpoints that create money movements **require**
  `Idempotency-Key`.
- Errors use one shape:

  ```json
  { "error": { "code": "INVALID_STATE_TRANSITION", "message": "Payment pay_123 is PENDING and cannot be refunded" } }
  ```

### Merchants

| Method | Path                     | Description                              |
| ------ | ------------------------ | ---------------------------------------- |
| POST   | `/api/v1/merchants`      | Register a merchant; returns API key once |
| GET    | `/api/v1/merchants/{id}` | Get merchant details                     |

### Customers

| Method | Path                     | Description                    |
| ------ | ------------------------ | ------------------------------ |
| POST   | `/api/v1/customers`      | Create a customer for the caller merchant |
| GET    | `/api/v1/customers/{id}` | Get a customer                 |

### Payment methods

| Method | Path                           | Description                                     |
| ------ | ------------------------------ | ----------------------------------------------- |
| POST   | `/api/v1/payment-methods`      | Attach a mock/tokenized method to a customer    |
| GET    | `/api/v1/payment-methods/{id}` | Get a payment method (token + last four only)   |

### Payments

| Method | Path                            | Description                                   |
| ------ | ------------------------------- | --------------------------------------------- |
| POST   | `/api/v1/payments`              | Create and process a payment (idempotent)     |
| GET    | `/api/v1/payments/{id}`         | Get a payment and its current status          |
| GET    | `/api/v1/payments?status=&from=&to=` | List the merchant's payments             |
| POST   | `/api/v1/payments/{id}/cancel`  | Cancel a payment still in `CREATED`           |

`POST /api/v1/payments`

```http
POST /api/v1/payments
Authorization: Bearer sk_test_…
Idempotency-Key: order-123-payment
Content-Type: application/json

{
  "amount": 100000,
  "currency": "INR",
  "customerId": "cust_123",
  "paymentMethodId": "pm_456"
}
```

Responses:

| Status | When                                                             |
| ------ | ---------------------------------------------------------------- |
| 201    | Payment created; body has `id` and `status`                      |
| same as first | Same key + same body seen before: original status and body replayed, with header `Idempotent-Replayed: true` |
| 200    | Same key, but the first attempt crashed after creating the payment: current state of that payment |
| 202    | Accepted but outcome not final yet (`PENDING`, e.g. timeout)     |
| 400    | Validation error (bad amount, unsupported currency)              |
| 401    | Missing or invalid API key                                       |
| 404    | Customer or payment method not found for this merchant           |
| 409    | Same key still in progress                                       |
| 422    | Same key reused with a **different** request body                |

```json
{
  "id": "pay_123",
  "status": "SUCCESS",
  "amount": 100000,
  "currency": "INR",
  "customerId": "cust_123",
  "paymentMethodId": "pm_456",
  "createdAt": "2026-09-24T10:00:00Z"
}
```

### Refunds

| Method | Path                            | Description                                  |
| ------ | ------------------------------- | -------------------------------------------- |
| POST   | `/api/v1/payments/{id}/refund`  | Refund a `SUCCESS` payment (idempotent)      |
| GET    | `/api/v1/refunds/{id}`          | Get a refund                                 |

```json
{ "amount": 100000, "reason": "Customer returned item" }
```

`409 INVALID_STATE_TRANSITION` if the payment is not in `SUCCESS`.

### Webhooks

| Method | Path                         | Description                                           |
| ------ | ---------------------------- | ----------------------------------------------------- |
| POST   | `/api/v1/webhooks/payment`   | Inbound events from the processor (signature-verified) |

Always returns `200` once the event is **stored**, including duplicates, so
the provider stops retrying. Processing happens after storage.

### Internal / admin (later)

| Method | Path                                | Description                     |
| ------ | ----------------------------------- | ------------------------------- |
| POST   | `/api/v1/admin/reconciliation/run`  | Trigger a reconciliation run    |
| POST   | `/api/v1/admin/retries/run`         | Trigger the payment retry job   |

The audit trail is exposed per payment instead: `GET /api/v1/payments/{id}/audit-logs`.

### Read models (fed by Kafka consumers, eventually consistent)

| Method | Path                                      | Description                        |
| ------ | ----------------------------------------- | ---------------------------------- |
| GET    | `/api/v1/analytics/daily?from=&to=`       | Merchant's daily totals            |
| GET    | `/api/v1/customers/{id}/notifications`    | Receipts sent to a customer        |

### Events published to Kafka

```text
PaymentCreated   PaymentSucceeded   PaymentFailed   PaymentCancelled
RefundRequested  RefundSucceeded    RefundFailed
```

Message format: `{eventId, type, occurredAt, data}`, where `data` holds
`paymentId`, `merchantId`, `customerId`, `status`, `amount`, `currency` (plus
`refundId` or `failureCode` where relevant). Headers `eventId` and `eventType`.

```text
PaymentService ──tx──▶ outbox_events ──relay──▶ topic paycore.events (key = paymentId)
                                                   │
                         ┌─────────────────────────┼──────────────────────────┐
                         ▼                         ▼                          ▼
              group paycore-analytics   group paycore-notifications   paycore.events.DLT
              merchant_daily_stats      notifications                  (records that failed 3 times,
                                                                        or are malformed)
```

- **One topic, keyed by payment id.** A payment's events and its refund's
  events land on the same partition, so they are consumed in order.
- **Publishing.** The relay sends a batch and waits until Kafka acknowledges
  every record (`acks=all`, idempotent producer). Only then does it mark the
  rows published, in the same transaction that locked them. If Kafka is down,
  the transaction rolls back and the events wait in the outbox. A crash after
  the acknowledgement but before the commit republishes the batch, so delivery
  is **at least once**.
- **Consumers are idempotent.** Each consumer group records `(consumer,
  eventId)` in `processed_events` in the same transaction as its effect, so a
  redelivered event changes nothing. At-least-once delivery plus idempotent
  consumers gives effectively-once processing.
- **Dead-letter topic.** A record that still fails after 3 attempts, or can't
  be parsed at all, goes to `paycore.events.DLT` with the exception in its
  headers, so one bad message never blocks its partition.
- **One relay at a time.** A Redis lock makes the relay run on one instance at
  a time, so batches are published in order. If Redis is down, relays on
  several instances can publish different batches (`SKIP LOCKED`) out of order;
  today's consumers only count and notify, so that is acceptable.
- **Merchant webhooks** are queued by the relay itself, in the same
  transaction that marks the event published, rather than by a Kafka consumer.
  Every event that reaches Kafka therefore also has a delivery row, even if the
  consumers are behind.

### Redis (an optimisation, never a dependency)

| Use | Key | Behaviour if Redis is down |
| --- | --- | --- |
| Rate limiting | `paycore:ratelimit:{merchantId}`: token bucket (capacity 100, refill 20/s), one atomic Lua script using Redis's clock | Requests are allowed |
| Merchant auth cache | `paycore:merchant-auth:{apiKeyHash}`, TTL 60s | Looked up in PostgreSQL |
| Idempotency replay cache | `paycore:idempotency:{merchantId}:{key}`, written after the DB commit, TTL 24h | Replayed from PostgreSQL |
| Job locks | `paycore:lock:{outbox-relay,reconciliation}`, `SET NX PX`, released only by the holder's token | Job runs anyway (safe: row locks still apply) |

All calls go through `RedisGuard`: after one failure it skips Redis for 5 seconds
(a minimal circuit breaker) so an outage costs one timeout, not one per request.

Trade-offs:
- A merchant's status change takes up to 60s to apply unless the cache entry is
  evicted (`MerchantAuthCache.evict`).
- The locks have no fencing token. If a holder stalls past the TTL, two instances
  can briefly run the same job; that is safe (row locks, idempotent writes), only
  event ordering across relay batches becomes best-effort again.
- Only *completed* idempotency keys are cached. Claiming a new key always goes
  through PostgreSQL's unique constraint.

---

## 5. Failure Scenarios

### 1. Duplicate payment request

**Situation.** The client sends `POST /payments` with
`Idempotency-Key: abc123`. PayCore charges the payment, but the response is
lost to a network timeout. The client retries with the same key. Or the
client double-clicks and two identical requests arrive at the same moment.

**Risk.** The customer is charged ₹1,000 twice.

**Design.**

1. Hash the normalized request body (`request_hash`).
2. `INSERT INTO idempotency_keys (merchant_id, key, request_hash, status='IN_PROGRESS')`.
   The **unique constraint on `(merchant_id, key)`** makes the database the
   referee: exactly one concurrent request wins the insert.
3. If the insert conflicts, read the existing row:
   - `COMPLETED` and hash matches → return the stored `response_status` and
     `response_body`. No new payment.
   - `COMPLETED` and hash differs → `422`: key reused for a different request.
   - `IN_PROGRESS` → `409`, client retries later.
4. When processing finishes, update the row to `COMPLETED` with the
   response and `payment_id`.
5. Expired keys (`expires_at < now()`) are cleaned up by a scheduled job.

Redis can later be put in front as a fast-path cache, but PostgreSQL's unique
constraint stays the source of truth.

### 2. Payment processor timeout

**Situation.** PayCore calls the processor and gets no answer within the
timeout (the mock does this 10% of the time).

**Risk.** A timeout does **not** mean failure; the charge may have gone
through. Marking it `FAILED` would be wrong, and blindly retrying could
charge twice.

**Design.**

- The payment stays `PENDING` with `failure_code = PROCESSOR_TIMEOUT` noted
  and `next_retry_at` set. The API returns `202` with status `PENDING`.
- Every call to the processor sends the **same `processor_reference`** as the
  processor's own idempotency key. A retry therefore either returns the
  result of the earlier attempt or makes the first attempt, never a second
  charge.
- Before retrying a charge, the retry worker first **asks** the processor
  for the status of that reference (`GET status`). It only resends the charge
  if the processor has never seen it.
- Retries use exponential backoff with jitter (e.g. 2s, 4s, 8s, 16s, 32s),
  capped at `max_attempts = 5`.
- After retries are exhausted, the retry job asks the processor one last time.
  The payment is only marked `FAILED` (`RETRIES_EXHAUSTED`) once the processor
  confirms it has no record of the charge.
- The result may also arrive by webhook at any time (Scenario 4); whichever
  source arrives first wins, the others become no-ops.

### 3. PayCore crashes after processor success

**Situation.**

```text
PayCore → Processor → SUCCESS → ✗ PayCore crashes before saving SUCCESS
```

**Risk.** The customer was charged, but PayCore still shows `PENDING`. On
restart it might charge again, or the merchant never learns the payment
succeeded.

**Design.**

- **Persist intent before acting.** The payment row (with its
  `processor_reference`) is committed as `PENDING` *before* the processor
  call. After a crash there is always a record of "we may have charged
  reference X".
- **Never re-charge blindly.** Because the reference is the processor's
  idempotency key, even a resent charge returns the original result.
- **A retry is always already scheduled.** Before every processor call,
  PayCore commits `attempt_count + 1` and `next_retry_at = now + backoff`.
  (A new payment gets `next_retry_at` when it becomes `PENDING`, before the
  first call.) So if the process dies mid-call, the retry job finds the
  payment once `next_retry_at` passes:

  ```sql
  SELECT id FROM payments
   WHERE status = 'PENDING' AND next_retry_at <= now();
  ```

  For each row, it asks the processor for the status of
  `processor_reference` and applies the answer through the normal state
  machine (`PENDING → SUCCESS` or `PENDING → FAILED`). Pushing
  `next_retry_at` forward under the row lock also works as a lease: another
  instance running the same job skips the payment while one is working on it.
- **Webhook as a second path.** The processor's `payment.succeeded`
  webhook will also move the payment to `SUCCESS` independently.
- **Atomic side effects.** `SUCCESS` status + ledger entries + audit log +
  outbox event are written in **one DB transaction**. A crash leaves either
  all of them or none of them, never "SUCCESS without ledger". The outbox
  relay then publishes to Kafka at-least-once; consumers de-duplicate by
  `eventId`.
- A **daily reconciliation** also compares all of the day's PayCore
  payments with the processor's settlement report, and checks
  `SUM(DEBIT) == SUM(CREDIT)` in the ledger. Mismatches are written to the
  audit log and alerted on.

### 4. Duplicate webhook

**Situation.** The processor sends `payment.succeeded` for `evt_789`, does
not receive our `200` in time, and sends it again. Or it sends events out of
order (`payment.failed` arrives after `payment.succeeded`).

**Risk.** Double ledger entries, double merchant notifications, or a
successful payment flipped to failed.

**Design.**

1. Verify the webhook signature. Invalid → `401`, not stored.
2. `INSERT INTO webhook_events (provider, event_id, …)`. The **unique
   constraint on `(provider, event_id)`** rejects the duplicate. On
   conflict, return `200` immediately and do nothing else.
3. Process the stored event through the state machine using a conditional
   update (`WHERE status = 'PENDING'`):
   - Payment already in the target state → mark event `IGNORED` (no-op).
   - Transition not allowed (e.g. `SUCCESS → FAILED`) → mark event
     `IGNORED`, write an audit log entry, flag for reconciliation.
4. Outbound webhooks to merchants carry a stable `event_id` so merchants can
   de-duplicate on their side too.

### 5. Refund request during payment processing

**Situation.** The merchant calls `POST /payments/pay_123/refund` while the
payment is still `PENDING`, or two refund requests for the same payment
arrive concurrently.

**Risk.** Refunding money that was never captured, a refund racing with the
success update, or refunding the same payment twice.

**Design.**

- **State check.** Refunds are only allowed from `SUCCESS`. A `PENDING`
  (or `CREATED`, `FAILED`, `CANCELLED`) payment returns
  `409 INVALID_STATE_TRANSITION` with the current status. The merchant can
  retry after the payment is final.
- **Locking.** The refund flow and the processor-result flow both take a
  lock on the payment row before changing state:

  ```sql
  SELECT * FROM payments WHERE id = :id FOR UPDATE;
  ```

  So "PENDING → SUCCESS" and "SUCCESS → REFUND_PENDING" are serialized; the
  refund always sees the committed state. When PayCore runs as several
  instances with work outside the DB transaction, this is extended to a
  **distributed lock** in Redis (`SET lock:payment:{id} <owner> NX PX 30000`,
  released only by its owner), still backed by the conditional
  `UPDATE … WHERE status = …` as the final guard.
- **No double refund.** The refund endpoint requires an `Idempotency-Key`,
  and the partial unique index on `refunds (payment_id)` for active refunds
  rejects a second one at the database level.
- **Refund failure.** If the processor rejects the refund, the refund row
  becomes `FAILED` and the payment returns to `SUCCESS`: the customer is
  still charged, and the merchant can try again.

### Other scenarios to handle later

- Kafka unavailable → events wait in `outbox_events`; nothing is lost.
- Merchant webhook endpoint down → `webhook_deliveries` retried with
  backoff, then marked `FAILED` for manual replay (dead-letter).
- Database unavailable → API returns `503`; no processor call is made
  without a committed `PENDING` row.
- Clock skew between instances → rely on DB `now()` for timestamps and
  retry scheduling.
- Fraud-rule false positive → payment `FAILED` with `FRAUD_SUSPECTED`;
  audit log records which rule fired.
