package com.paycore.reconciliation;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(
        Instant ranAt,
        int paymentsChecked,
        int refundsResolved,
        int refundsResent,
        List<String> processorMismatches,
        List<String> paymentsMissingLedger,
        List<String> refundsMissingLedger,
        List<String> unbalancedTransactions) {

    public boolean clean() {
        return processorMismatches.isEmpty() && paymentsMissingLedger.isEmpty() && refundsMissingLedger.isEmpty()
                && unbalancedTransactions.isEmpty();
    }
}
