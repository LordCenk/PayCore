package com.paycore.fraud;

import java.util.Optional;

public interface FraudRule {

    String name();

    /** Empty means the rule has no objection. */
    Optional<FraudDecision> evaluate(FraudContext context);
}
