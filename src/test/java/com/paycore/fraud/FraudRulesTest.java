package com.paycore.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.paycore.config.PayCoreProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FraudRulesTest {

    private static final PayCoreProperties PROPERTIES = new PayCoreProperties(Set.of("INR"), null, null, null,
            new PayCoreProperties.Fraud(10_000, 5, Duration.ofMinutes(1), Set.of("tok_blocked")), null, null, null);

    private static FraudContext context(long amount, String token) {
        return new FraudContext("m", "c", token, amount, "INR");
    }

    @Test
    void amountLimitBlocksAboveTheLimitOnly() {
        AmountLimitRule rule = new AmountLimitRule(PROPERTIES);
        assertThat(rule.evaluate(context(10_000, "tok"))).isEmpty();
        assertThat(rule.evaluate(context(10_001, "tok")))
                .hasValueSatisfying(d -> assertThat(d.action()).isEqualTo(FraudDecision.Action.BLOCK));
    }

    @Test
    void blockedPaymentMethodIsBlocked() {
        BlockedPaymentMethodRule rule = new BlockedPaymentMethodRule(PROPERTIES);
        assertThat(rule.evaluate(context(100, "tok_success"))).isEmpty();
        assertThat(rule.evaluate(context(100, "tok_blocked"))).isPresent();
    }

    @Test
    void serviceBlocksIfAnyRuleBlocksAndKeepsFlags() {
        FraudRule flagger = new FraudRule() {
            public String name() { return "F"; }
            public java.util.Optional<FraudDecision> evaluate(FraudContext c) {
                return java.util.Optional.of(FraudDecision.flag("F", "odd"));
            }
        };
        FraudService service = new FraudService(List.of(new AmountLimitRule(PROPERTIES), flagger));

        FraudResult allowed = service.evaluate(context(100, "tok"));
        assertThat(allowed.block()).isEmpty();
        assertThat(allowed.flags()).hasSize(1);

        assertThat(service.evaluate(context(50_000, "tok")).block()).isPresent();
    }
}
