package com.paycore.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerServiceTest {

    private static LedgerEntry entry(LedgerEntry.Type type, long amount) {
        return new LedgerEntry("tx_1", "pay_1", null, "ACC", type, amount, "INR", Instant.now());
    }

    @Test
    void balancedTransactionIsAccepted() {
        assertThatCode(() -> LedgerService.assertBalanced(List.of(
                entry(LedgerEntry.Type.DEBIT, 1000), entry(LedgerEntry.Type.CREDIT, 1000))))
                .doesNotThrowAnyException();
    }

    @Test
    void unbalancedTransactionIsRejected() {
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(
                entry(LedgerEntry.Type.DEBIT, 1000), entry(LedgerEntry.Type.CREDIT, 999))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void nonPositiveAmountIsRejected() {
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(
                entry(LedgerEntry.Type.DEBIT, 0), entry(LedgerEntry.Type.CREDIT, 0))))
                .isInstanceOf(IllegalStateException.class);
    }
}
