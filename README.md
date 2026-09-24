# PayCore

A simplified payment processing backend, built to handle the failures real
payment systems face: duplicate requests, processor timeouts, crashes midway
through a payment, and webhooks that arrive twice or out of order.

The full design (state machine, schema, API, failure scenarios) is in
[`PAYCORE_DESIGN.md`](PAYCORE_DESIGN.md).

**Stack:** Java 21, Spring Boot 4, PostgreSQL 16, Kafka 4, Redis 7, Flyway, JUnit 5.

## What's implemented

| Feature | How |
| --- | --- |
| Payment creation | `POST /api/v1/payments`, validated, fraud-checked, processed synchronously |
| Transaction lifecycle | Explicit state machine (`PaymentStatus`); illegal transitions throw |
| Idempotency keys | Unique `(merchant_id, key)` constraint decides the winner; responses are replayed |
| Payment retries | Exponential backoff with jitter; each retry asks the processor before re-sending |
| Webhooks | Inbound (HMAC-verified, de-duplicated) and outbound (signed, retried) |
| Refunds | Full refunds, row-locked against concurrent payment updates |
| Reconciliation | Compares PayCore with the processor, checks ledger invariants, resolves stuck refunds |
| Fraud-rule simulation | Amount limit, velocity, deny-listed tokens (block); unusual amount (flag) |
| Distributed locking | `SELECT ... FOR UPDATE` row locks, `SKIP LOCKED` job queues, `next_retry_at` leases, Redis locks so the outbox relay and reconciliation run on one instance at a time |
| Rate limiting | Per-merchant token bucket in Redis (atomic Lua script); `429` + `Retry-After` |
| Caching | API-key lookups and completed idempotent responses cached in Redis |
| Audit logs | Append-only row for every state change, written in the same transaction |
| Double-entry ledger | Every payment and refund is a balanced debit/credit pair |
| Transactional outbox | Events are stored with the state change and relayed afterwards |
| Kafka | Outbox relay publishes to `paycore.events` keyed by payment id; broker must ack before an event counts as published |
| Event consumers | Analytics (daily merchant totals) and notifications (customer receipts), each idempotent, with a dead-letter topic |

Redis is only an optimisation. If it is unreachable, every feature falls back to
PostgreSQL (rate limiting is skipped), and a small circuit breaker stops PayCore
from waiting on Redis for each request.

**Not yet:** partial refunds.

## Observability

- **Metrics** at `/actuator/prometheus`: payment transitions by status and failure
  code (counted only after commit), processor latency and outcome, outbox backlog
  and oldest-event age, rate-limit rejections, idempotent replays, webhook
  deliveries, Redis fallbacks, plus Spring's HTTP and JVM metrics.
- **Dashboard and alerts:** `docker compose --profile observability up -d` starts
  Prometheus (http://localhost:9090) and Grafana (http://localhost:3000, dashboard
  "PayCore"). Alert rules in `observability/alerts.yml`: outbox lagging, high
  failure rate, processor timeouts, reconciliation problems.
- **Logs:** every line carries the request id (`X-Request-Id`, echoed or
  generated) and the merchant id. Set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` for
  JSON logs.
- **Health:** `/actuator/health` (with `/liveness` and `/readiness` probes). Redis
  is left out on purpose: it's optional, so its outage mustn't fail the app.

## Run it

You need Java 21 and Docker (for PostgreSQL, Redis and Kafka).

```bash
docker compose up -d          # PostgreSQL (paycore + paycore_test databases), Redis, single-node Kafka
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080` and applies the schema with Flyway.

| Setting | Default |
| --- | --- |
| `PAYCORE_DB_URL`, `PAYCORE_DB_USER`, `PAYCORE_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/paycore`, `paycore`, `paycore` |
| `PAYCORE_REDIS_HOST`, `PAYCORE_REDIS_PORT` | `localhost`, `6379` |
| `PAYCORE_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `PAYCORE_EVENTS_PUBLISHER` | `kafka`; set `logging` to run without Kafka (events are logged instead, and the consumers are off) |

If Kafka is down, payments still work: events wait in the outbox and are
published once the broker is back.

## Test it

```bash
mvn test
```

80 tests: unit tests for the state machine, ledger and fraud rules, plus
integration tests (`*IT`) that run the whole app against the `paycore_test`
database (override with `PAYCORE_TEST_DB_URL`) and Redis database 1 (`PAYCORE_TEST_REDIS_HOST`/`_PORT`). Every failure scenario in the
design doc has a test, including concurrent duplicate requests and concurrent
refunds. The Kafka tests use an in-process broker, so they don't need Docker.

## Try it

```bash
# 1. Register a merchant; keep the apiKey (it's shown only once)
curl -s -X POST localhost:8080/api/v1/merchants -H 'Content-Type: application/json' \
  -d '{"name":"DemoStore","email":"payments@demostore.com"}'
export KEY=sk_test_...

# 2. Create a customer and a tokenized payment method
curl -s -X POST localhost:8080/api/v1/customers -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' -d '{"name":"Asha","email":"asha@example.com"}'
curl -s -X POST localhost:8080/api/v1/payment-methods -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust_...","type":"CARD","token":"tok_success","lastFour":"4242"}'

# 3. Pay ₹1,000 (amounts are in paise). Send it twice: you get the same payment back.
curl -s -X POST localhost:8080/api/v1/payments -H "Authorization: Bearer $KEY" \
  -H 'Idempotency-Key: order-123' -H 'Content-Type: application/json' \
  -d '{"amount":100000,"currency":"INR","customerId":"cust_...","paymentMethodId":"pm_..."}'

# 4. Refund it, then look at the ledger and the audit trail
curl -s -X POST localhost:8080/api/v1/payments/pay_.../refund -H "Authorization: Bearer $KEY" \
  -H 'Idempotency-Key: refund-123' -H 'Content-Type: application/json' -d '{"reason":"returned"}'
curl -s localhost:8080/api/v1/payments/pay_.../ledger -H "Authorization: Bearer $KEY"
curl -s localhost:8080/api/v1/payments/pay_.../audit-logs -H "Authorization: Bearer $KEY"

# 5. After a moment, the Kafka consumers have caught up
curl -s localhost:8080/api/v1/analytics/daily -H "Authorization: Bearer $KEY"
curl -s localhost:8080/api/v1/customers/cust_.../notifications -H "Authorization: Bearer $KEY"

# 6. Run reconciliation
curl -s -X POST localhost:8080/api/v1/admin/reconciliation/run -H 'X-Admin-Key: admin_dev_key'
```

### Mock processor test tokens

The mock gateway never moves money. Use these tokens on a payment method to
force an outcome; any other `tok_...` gets 70% success, 20% decline, 10% timeout.

| Token | Behaviour |
| --- | --- |
| `tok_success` | Charge succeeds |
| `tok_decline` | Charge is declined |
| `tok_timeout` | First call never reaches the gateway; the retry succeeds |
| `tok_timeout_after_charge` | Charge succeeds but the response is lost; the retry finds it |
| `tok_timeout_always` | Gateway unreachable; payment fails once retries are exhausted |
| `tok_refund_decline` | Charge succeeds, refunds are declined |
| `tok_blocked` | Blocked by the fraud deny list |

## API

| Method | Path | |
| --- | --- | --- |
| POST | `/api/v1/merchants` | Register (public); returns API key and webhook secret |
| GET | `/api/v1/merchants/{id}` | Own merchant only |
| POST/GET | `/api/v1/customers`, `/api/v1/customers/{id}` | |
| POST/GET | `/api/v1/payment-methods`, `/api/v1/payment-methods/{id}` | Tokens only, never card numbers |
| POST | `/api/v1/payments` | Requires `Idempotency-Key`. 201 final, 202 pending |
| GET | `/api/v1/payments`, `/api/v1/payments/{id}` | List supports `?status=&limit=` |
| POST | `/api/v1/payments/{id}/cancel` | Only from `CREATED` |
| GET | `/api/v1/payments/{id}/ledger`, `/api/v1/payments/{id}/audit-logs` | |
| POST | `/api/v1/payments/{id}/refund` | Requires `Idempotency-Key` |
| GET | `/api/v1/refunds/{id}` | |
| GET | `/api/v1/analytics/daily?from=&to=` | Daily totals, from the analytics consumer |
| GET | `/api/v1/customers/{id}/notifications` | Receipts, from the notification consumer |
| POST | `/api/v1/webhooks/payment` | From the processor; header `X-Processor-Signature` = hex HMAC-SHA256 of the body |
| POST | `/api/v1/admin/reconciliation/run`, `/api/v1/admin/retries/run` | Header `X-Admin-Key` |

Outbound webhooks to merchants carry `X-PayCore-Event-Id`,
`X-PayCore-Event-Type` and `X-PayCore-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`.

## Project layout

```text
src/main/java/com/paycore/
  payment/         state machine, entity, PaymentStateService (transactions), PaymentService (orchestration), retry job
  refund/          refund lifecycle
  idempotency/     key claiming, replay, request handler
  processor/       PaymentProcessor interface + MockPaymentProcessor
  fraud/           rules and FraudService
  ledger/          double-entry ledger
  outbox/          transactional outbox, relay, Kafka publisher
  events/          Kafka config (topics, dead-letter handling), idempotent-consumer support
  analytics/       analytics consumer + daily stats API
  notification/    notification consumer + API
  redis/           RedisGuard (fail-open + circuit breaker), DistributedLock
  ratelimit/       token-bucket rate limiter + interceptor
  observability/   metrics, metered processor decorator, outbox gauges, request-id filter
observability/     Prometheus config + alert rules, Grafana provisioning and dashboard
  webhook/         inbound (processor) and outbound (merchant) webhooks
  reconciliation/  reconciliation service/job, admin endpoints
  audit/           audit log
  merchant/ customer/ paymentmethod/ auth/ common/ config/
src/main/resources/db/migration/   Flyway schema
```
