#!/usr/bin/env bash
# End-to-end check against a running PayCore (default http://localhost:8080):
# payment -> refund -> ledger -> reconciliation, then the Kafka consumers' read models.
# Usage: scripts/smoke-test.sh [base-url] [admin-key]
set -euo pipefail

BASE=${1:-http://localhost:8080}
ADMIN_KEY=${2:-admin_dev_key}
JSON='Content-Type: application/json'

json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "Waiting for $BASE ..."
for _ in $(seq 1 90); do
  curl -sf "$BASE/actuator/health" > /dev/null && break
  sleep 2
done
curl -sf "$BASE/actuator/health" > /dev/null || fail "PayCore is not healthy"

MERCHANT=$(curl -sf -X POST "$BASE/api/v1/merchants" -H "$JSON" \
  -d "{\"name\":\"Smoke\",\"email\":\"smoke-$(date +%s%N)@example.com\"}")
AUTH="Authorization: Bearer $(echo "$MERCHANT" | json '["apiKey"]')"
CUSTOMER=$(curl -sf -X POST "$BASE/api/v1/customers" -H "$JSON" -H "$AUTH" \
  -d '{"name":"Asha","email":"asha@example.com"}' | json '["id"]')
METHOD=$(curl -sf -X POST "$BASE/api/v1/payment-methods" -H "$JSON" -H "$AUTH" \
  -d "{\"customerId\":\"$CUSTOMER\",\"type\":\"CARD\",\"token\":\"tok_success\",\"lastFour\":\"4242\"}" | json '["id"]')

PAY_BODY="{\"amount\":100000,\"currency\":\"INR\",\"customerId\":\"$CUSTOMER\",\"paymentMethodId\":\"$METHOD\"}"
PAYMENT=$(curl -sf -X POST "$BASE/api/v1/payments" -H "$JSON" -H "$AUTH" -H 'Idempotency-Key: smoke-1' -d "$PAY_BODY")
PAYMENT_ID=$(echo "$PAYMENT" | json '["id"]')
[ "$(echo "$PAYMENT" | json '["status"]')" = SUCCESS ] || fail "payment not SUCCESS: $PAYMENT"
echo "payment $PAYMENT_ID SUCCESS"

REPLAY_ID=$(curl -sf -X POST "$BASE/api/v1/payments" -H "$JSON" -H "$AUTH" -H 'Idempotency-Key: smoke-1' -d "$PAY_BODY" | json '["id"]')
[ "$REPLAY_ID" = "$PAYMENT_ID" ] || fail "idempotent replay returned a different payment"
echo "idempotent replay OK"

REFUND=$(curl -sf -X POST "$BASE/api/v1/payments/$PAYMENT_ID/refund" -H "$JSON" -H "$AUTH" -H 'Idempotency-Key: smoke-r1' -d '{}')
[ "$(echo "$REFUND" | json '["status"]')" = SUCCEEDED ] || fail "refund not SUCCEEDED: $REFUND"
echo "refund SUCCEEDED"

ENTRIES=$(curl -sf "$BASE/api/v1/payments/$PAYMENT_ID/ledger" -H "$AUTH" | json '.__len__()')
[ "$ENTRIES" = 4 ] || fail "expected 4 ledger entries, got $ENTRIES"
echo "ledger balanced (4 entries)"

CLEAN=$(curl -sf -X POST "$BASE/api/v1/admin/reconciliation/run" -H "X-Admin-Key: $ADMIN_KEY" \
  | python3 -c "import sys, json; r = json.load(sys.stdin); print(not (r['processorMismatches'] or r['unbalancedTransactions']))")
[ "$CLEAN" = True ] || fail "reconciliation found problems"
echo "reconciliation clean"

echo "Waiting for Kafka consumers ..."
for _ in $(seq 1 60); do
  STATS=$(curl -sf "$BASE/api/v1/analytics/daily" -H "$AUTH")
  NOTES=$(curl -sf "$BASE/api/v1/customers/$CUSTOMER/notifications" -H "$AUTH" | json '.__len__()')
  if [ "$(echo "$STATS" | json '[0]["refundsSucceeded"]' 2>/dev/null || echo 0)" = 1 ] && [ "$NOTES" = 2 ]; then
    echo "analytics and notifications caught up"
    echo "SMOKE TEST PASSED"
    exit 0
  fi
  sleep 2
done
fail "Kafka consumers did not catch up (analytics: $STATS, notifications: $NOTES)"
