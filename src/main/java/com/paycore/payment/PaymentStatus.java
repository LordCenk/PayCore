package com.paycore.payment;

import java.util.EnumSet;
import java.util.Set;

/** The payment state machine. See PAYCORE_DESIGN.md, section 2. */
public enum PaymentStatus {
    CREATED,
    PENDING,
    SUCCESS,
    FAILED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(PENDING, FAILED, CANCELLED);
            case PENDING -> EnumSet.of(SUCCESS, FAILED);
            case SUCCESS -> EnumSet.of(REFUND_PENDING);
            case REFUND_PENDING -> EnumSet.of(REFUNDED, SUCCESS);
            case FAILED, CANCELLED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
