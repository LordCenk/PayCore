package com.paycore.fraud;

import com.paycore.payment.PaymentRepository;
import com.paycore.payment.PaymentStatus;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Flags (but allows) an amount far above what this customer usually pays. */
@Component
public class UnusualAmountRule implements FraudRule {

    static final int MIN_HISTORY = 3;
    static final int MULTIPLIER = 10;

    private final PaymentRepository payments;

    public UnusualAmountRule(PaymentRepository payments) {
        this.payments = payments;
    }

    @Override
    public String name() {
        return "UNUSUAL_AMOUNT";
    }

    @Override
    public Optional<FraudDecision> evaluate(FraudContext context) {
        long history = payments.countByCustomerIdAndStatus(context.customerId(), PaymentStatus.SUCCESS);
        if (history < MIN_HISTORY) {
            return Optional.empty();
        }
        double average = payments.averageAmount(context.customerId(), PaymentStatus.SUCCESS);
        if (context.amount() > average * MULTIPLIER) {
            return Optional.of(FraudDecision.flag(name(),
                    "Amount is more than " + MULTIPLIER + "x the customer's average of " + Math.round(average)));
        }
        return Optional.empty();
    }
}
