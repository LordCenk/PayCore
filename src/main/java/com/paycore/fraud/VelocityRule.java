package com.paycore.fraud;

import com.paycore.config.PayCoreProperties;
import com.paycore.payment.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Blocks a customer who is creating payments faster than a human plausibly would. */
@Component
public class VelocityRule implements FraudRule {

    private final PaymentRepository payments;
    private final int limit;
    private final Duration window;
    private final Clock clock;

    public VelocityRule(PaymentRepository payments, PayCoreProperties properties, Clock clock) {
        this.payments = payments;
        this.limit = properties.fraud().velocityLimit();
        this.window = properties.fraud().velocityWindow();
        this.clock = clock;
    }

    @Override
    public String name() {
        return "VELOCITY";
    }

    @Override
    public Optional<FraudDecision> evaluate(FraudContext context) {
        long recent = payments.countByCustomerIdAndCreatedAtAfter(context.customerId(), clock.instant().minus(window));
        if (recent >= limit) {
            return Optional.of(FraudDecision.block(name(),
                    recent + " payments from this customer in the last " + window.toSeconds() + "s"));
        }
        return Optional.empty();
    }
}
