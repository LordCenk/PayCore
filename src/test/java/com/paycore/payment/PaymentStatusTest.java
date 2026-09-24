package com.paycore.payment;

import static com.paycore.payment.PaymentStatus.CANCELLED;
import static com.paycore.payment.PaymentStatus.CREATED;
import static com.paycore.payment.PaymentStatus.FAILED;
import static com.paycore.payment.PaymentStatus.PENDING;
import static com.paycore.payment.PaymentStatus.REFUNDED;
import static com.paycore.payment.PaymentStatus.REFUND_PENDING;
import static com.paycore.payment.PaymentStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    /** The transition table from PAYCORE_DESIGN.md, section 2. Anything not listed is illegal. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PENDING, FAILED, CANCELLED),
            PENDING, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED, SUCCESS),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    @Test
    void everyTransitionMatchesTheDesign() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    void terminalStates() {
        assertThat(EnumSet.allOf(PaymentStatus.class).stream().filter(PaymentStatus::isTerminal))
                .containsExactlyInAnyOrder(FAILED, CANCELLED, REFUNDED);
    }

    @Test
    void illegalTransitionIsRejectedAndStateIsUnchanged() {
        Payment payment = new Payment("pay_1", "m", "c", "pm", 100, "INR", "MOCK", "ref_1", null, Instant.now());
        payment.transitionTo(PENDING, Instant.now());
        payment.transitionTo(FAILED, Instant.now());

        assertThatThrownBy(() -> payment.transitionTo(SUCCESS, Instant.now()))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("FAILED");
        assertThat(payment.getStatus()).isEqualTo(FAILED);
    }
}
