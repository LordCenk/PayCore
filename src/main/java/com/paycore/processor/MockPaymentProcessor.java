package com.paycore.processor;

import com.paycore.common.Ids;
import com.paycore.config.PayCoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real gateway. No money moves.
 *
 * <p>Its records live in the {@code mock_gateway_*} tables: those belong to the simulated gateway, not to
 * PayCore, and exist so the gateway remembers charges across PayCore restarts like a real one would.
 * Writes are auto-committed and never join a PayCore transaction.
 *
 * <p>Test tokens force an outcome (like a real gateway's test cards):
 * <ul>
 *   <li>{@code tok_success}: charge succeeds</li>
 *   <li>{@code tok_decline}: charge is declined</li>
 *   <li>{@code tok_timeout}: first call times out before reaching the gateway, later calls succeed</li>
 *   <li>{@code tok_timeout_after_charge}: the charge succeeds but the response is lost (timeout)</li>
 *   <li>{@code tok_timeout_always}: the gateway never answers and never charges</li>
 *   <li>{@code tok_refund_decline}: charge succeeds, refunds are declined</li>
 * </ul>
 * Any other token uses the configured success / decline / timeout rates.
 */
@Component
public class MockPaymentProcessor implements PaymentProcessor {

    private final PayCoreProperties.Processor config;
    private final JdbcTemplate jdbc;
    /** Only drives the tok_timeout scenario ("first call is lost"), so it can stay in memory. */
    private final Map<String, Integer> timeoutsInjected = new ConcurrentHashMap<>();

    public MockPaymentProcessor(PayCoreProperties properties, JdbcTemplate jdbc) {
        this.config = properties.processor();
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "MOCK_GATEWAY";
    }

    @Override
    public ChargeResult charge(ChargeRequest request) throws ProcessorTimeoutException {
        Optional<ChargeResult> existing = getCharge(request.reference());
        if (existing.isPresent()) {
            return existing.get(); // idempotent: same reference, same result, no second charge
        }
        String token = request.paymentMethodToken();
        switch (token) {
            case "tok_success", "tok_refund_decline" -> {
                return record(request, succeeded());
            }
            case "tok_decline" -> {
                return record(request, declined());
            }
            case "tok_timeout" -> {
                if (timeoutsInjected.merge(request.reference(), 1, Integer::sum) == 1) {
                    throw new ProcessorTimeoutException("Gateway timeout (request never arrived)");
                }
                return record(request, succeeded());
            }
            case "tok_timeout_always" -> throw new ProcessorTimeoutException("Gateway timeout (unreachable)");
            case "tok_timeout_after_charge" -> {
                record(request, succeeded());
                throw new ProcessorTimeoutException("Gateway timeout (charge captured, response lost)");
            }
            default -> {
                double roll = ThreadLocalRandom.current().nextDouble();
                if (roll < config.successRate()) {
                    return record(request, succeeded());
                }
                if (roll < config.successRate() + config.declineRate()) {
                    return record(request, declined());
                }
                if (ThreadLocalRandom.current().nextBoolean()) {
                    record(request, succeeded());
                }
                throw new ProcessorTimeoutException("Gateway timeout");
            }
        }
    }

    @Override
    public Optional<ChargeResult> getCharge(String reference) {
        List<ChargeResult> rows = jdbc.query(
                "SELECT outcome, provider_id, decline_code, message FROM mock_gateway_charges WHERE reference = ?",
                (rs, i) -> new ChargeResult(Outcome.valueOf(rs.getString("outcome")), rs.getString("provider_id"),
                        rs.getString("decline_code"), rs.getString("message")),
                reference);
        return rows.stream().findFirst();
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        Optional<RefundResult> existing = getRefund(request.reference());
        if (existing.isPresent()) {
            return existing.get();
        }
        List<String> tokens = jdbc.queryForList(
                "SELECT token FROM mock_gateway_charges WHERE reference = ? AND outcome = 'SUCCEEDED'",
                String.class, request.chargeReference());
        RefundResult result;
        if (tokens.isEmpty()) {
            result = new RefundResult(Outcome.DECLINED, null, "CHARGE_NOT_FOUND", "No successful charge to refund");
        } else if ("tok_refund_decline".equals(tokens.getFirst())) {
            result = new RefundResult(Outcome.DECLINED, null, "REFUND_DECLINED", "Refund declined by gateway");
        } else {
            result = new RefundResult(Outcome.SUCCEEDED, "mock_re_" + Ids.random(16), null, null);
        }
        jdbc.update("""
                INSERT INTO mock_gateway_refunds (reference, charge_reference, outcome, provider_id, decline_code,
                                                  message, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (reference) DO NOTHING
                """, request.reference(), request.chargeReference(), result.outcome().name(),
                result.providerRefundId(), result.declineCode(), result.message(), request.amount());
        return getRefund(request.reference()).orElseThrow(); // a concurrent call may have won the insert
    }

    @Override
    public Optional<RefundResult> getRefund(String reference) {
        List<RefundResult> rows = jdbc.query(
                "SELECT outcome, provider_id, decline_code, message FROM mock_gateway_refunds WHERE reference = ?",
                (rs, i) -> new RefundResult(Outcome.valueOf(rs.getString("outcome")), rs.getString("provider_id"),
                        rs.getString("decline_code"), rs.getString("message")),
                reference);
        return rows.stream().findFirst();
    }

    /** Stores the result unless a concurrent call already did, and returns whichever result was stored. */
    private ChargeResult record(ChargeRequest request, ChargeResult result) {
        jdbc.update("""
                INSERT INTO mock_gateway_charges (reference, outcome, provider_id, decline_code, message, token,
                                                  amount, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (reference) DO NOTHING
                """, request.reference(), result.outcome().name(), result.providerPaymentId(), result.declineCode(),
                result.message(), request.paymentMethodToken(), request.amount(), request.currency());
        return getCharge(request.reference()).orElseThrow();
    }

    private static ChargeResult succeeded() {
        return new ChargeResult(Outcome.SUCCEEDED, "mock_ch_" + Ids.random(16), null, null);
    }

    private static ChargeResult declined() {
        return new ChargeResult(Outcome.DECLINED, null, "CARD_DECLINED", "The card was declined");
    }
}
