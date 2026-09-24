package com.paycore.fraud;

import com.paycore.config.PayCoreProperties;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BlockedPaymentMethodRule implements FraudRule {

    private final Set<String> blockedTokens;

    public BlockedPaymentMethodRule(PayCoreProperties properties) {
        this.blockedTokens = properties.fraud().blockedTokens() == null ? Set.of() : properties.fraud().blockedTokens();
    }

    @Override
    public String name() {
        return "BLOCKED_PAYMENT_METHOD";
    }

    @Override
    public Optional<FraudDecision> evaluate(FraudContext context) {
        if (blockedTokens.contains(context.paymentMethodToken())) {
            return Optional.of(FraudDecision.block(name(), "Payment method is on the deny list"));
        }
        return Optional.empty();
    }
}
