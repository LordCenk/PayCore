package com.paycore.fraud;

import java.util.List;
import java.util.Optional;

public record FraudResult(List<FraudDecision> decisions) {

    public Optional<FraudDecision> block() {
        return decisions.stream().filter(d -> d.action() == FraudDecision.Action.BLOCK).findFirst();
    }

    public List<FraudDecision> flags() {
        return decisions.stream().filter(d -> d.action() == FraudDecision.Action.FLAG).toList();
    }
}
