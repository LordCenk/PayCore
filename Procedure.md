# PayCore

## Build and test

- Java 21, Spring Boot 4, PostgreSQL 16. Schema changes go in a new Flyway migration
  under `src/main/resources/db/migration/`; never edit a migration that has been pushed.
- `./mvnw test` runs unit tests and the `*IT` integration tests, which need the
  `paycore_test` database and Redis (`docker compose up -d`, or `PAYCORE_TEST_DB_URL`
  and `PAYCORE_TEST_REDIS_HOST`). Kafka tests use an embedded broker.
- Every Redis call goes through `RedisGuard`: Redis must never be required for a payment.
- `scripts/smoke-test.sh` checks a running instance end to end; CI runs it against the
  docker compose stack (`--profile app`).
- Payment state changes go through `PaymentStateService` / `RefundStateService`
  (row lock, then status + ledger + audit + outbox in one transaction). Processor
  calls happen outside transactions.
