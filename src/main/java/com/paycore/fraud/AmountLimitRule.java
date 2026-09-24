package com.paycore.fraud;

import com.paycore.config.PayCoreProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AmountLimitRule implements FraudRule {

    private final long maxAmount;

    public AmountLimitRule(PayCoreProperties properties) {
        this.maxAmount = properties.fraud().maxAmount();
    }

    @Override
    public String name() {
        return "AMOUNT_LIMIT";
    }

    @Override
    public Optional<FraudDecision> evaluate(FraudContext context) {
        if (context.amount() > maxAmount) {
            return Optional.of(FraudDecision.block(name(), "Amount exceeds limit of " + maxAmount));
        }
        return Optional.empty();
    }
}
