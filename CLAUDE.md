# PayCore

Design document: `PAYCORE_DESIGN.md`.

## Git commits

- Author and commit as the repo owner:
  `git config user.name "Shashank Agrawal"` and
  `git config user.email "154488267+LordCenk@users.noreply.github.com"`
  before the first commit in a session.
- Never add a `Co-Authored-By: Claude` trailer, a `Claude-Session` line, or any
  other Claude/AI attribution to commit messages or PR descriptions.

## Build and test

- Java 21, Spring Boot 4, PostgreSQL 16. Schema changes go in a new Flyway migration
  under `src/main/resources/db/migration/`; never edit a migration that has been pushed.
- `./mvnw test` runs unit tests and the `*IT` integration tests, which need the
  `paycore_test` database (`docker compose up -d`, or `PAYCORE_TEST_DB_URL`).
- Payment state changes go through `PaymentStateService` / `RefundStateService`
  (row lock, then status + ledger + audit + outbox in one transaction). Processor
  calls happen outside transactions.
