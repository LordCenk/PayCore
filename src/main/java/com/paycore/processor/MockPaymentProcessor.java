package com.paycore.processor;

import com.paycore.common.Ids;
import com.paycore.config.PayCoreProperties;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for a real gateway. No money moves.
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
    private final Map<String, ChargeResult> charges = new ConcurrentHashMap<>();
    private final Map<String, String> chargeTokens = new ConcurrentHashMap<>();
    private final Map<String, RefundResult> refunds = new ConcurrentHashMap<>();
    private final Map<String, Integer> timeoutsInjected = new ConcurrentHashMap<>();

    public MockPaymentProcessor(PayCoreProperties properties) {
        this.config = properties.processor();
    }

    @Override
    public String name() {
        return "MOCK_GATEWAY";
    }

    @Override
    public ChargeResult charge(ChargeRequest request) throws ProcessorTimeoutException {
        ChargeResult existing = charges.get(request.reference());
        if (existing != null) {
            return existing; // idempotent: same reference, same result, no second charge
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
        return Optional.ofNullable(charges.get(reference));
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        return refunds.computeIfAbsent(request.reference(), ref -> {
            ChargeResult charge = charges.get(request.chargeReference());
            if (charge == null || charge.outcome() != Outcome.SUCCEEDED) {
                return new RefundResult(Outcome.DECLINED, null, "CHARGE_NOT_FOUND", "No successful charge to refund");
            }
            if ("tok_refund_decline".equals(chargeTokens.get(request.chargeReference()))) {
                return new RefundResult(Outcome.DECLINED, null, "REFUND_DECLINED", "Refund declined by gateway");
            }
            return new RefundResult(Outcome.SUCCEEDED, "mock_re_" + Ids.random(16), null, null);
        });
    }

    @Override
    public Optional<RefundResult> getRefund(String reference) {
        return Optional.ofNullable(refunds.get(reference));
    }

    /** Test/demo hook: how many distinct charges the gateway has captured. */
    public int capturedChargeCount() {
        return (int) charges.values().stream().filter(c -> c.outcome() == Outcome.SUCCEEDED).count();
    }

    private ChargeResult record(ChargeRequest request, ChargeResult result) {
        ChargeResult stored = charges.putIfAbsent(request.reference(), result);
        chargeTokens.putIfAbsent(request.reference(), request.paymentMethodToken());
        return stored != null ? stored : result;
    }

    private static ChargeResult succeeded() {
        return new ChargeResult(Outcome.SUCCEEDED, "mock_ch_" + Ids.random(16), null, null);
    }

    private static ChargeResult declined() {
        return new ChargeResult(Outcome.DECLINED, null, "CARD_DECLINED", "The card was declined");
    }
}
