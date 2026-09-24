package com.paycore.fraud;

import java.util.List;
import org.springframework.stereotype.Service;

/** Runs every rule; a single BLOCK stops the payment, FLAGs are only audited. */
@Service
public class FraudService {

    private final List<FraudRule> rules;

    public FraudService(List<FraudRule> rules) {
        this.rules = rules;
    }

    public FraudResult evaluate(FraudContext context) {
        return new FraudResult(rules.stream()
                .map(rule -> rule.evaluate(context))
                .flatMap(java.util.Optional::stream)
                .toList());
    }
}
