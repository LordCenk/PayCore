package com.paycore.fraud;

public record FraudDecision(Action action, String rule, String reason) {

    public enum Action { BLOCK, FLAG }

    public static FraudDecision block(String rule, String reason) {
        return new FraudDecision(Action.BLOCK, rule, reason);
    }

    public static FraudDecision flag(String rule, String reason) {
        return new FraudDecision(Action.FLAG, rule, reason);
    }
}
