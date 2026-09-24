package com.paycore.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByPaymentIdOrderByIdAsc(String paymentId);

    boolean existsByPaymentIdAndRefundIdIsNull(String paymentId);

    boolean existsByRefundId(String refundId);

    /** Transactions whose debits and credits don't match. Must always be empty. */
    @Query(value = """
            SELECT transaction_id FROM ledger_entries
            GROUP BY transaction_id
            HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END)
                <> SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END)
            """, nativeQuery = true)
    List<String> findUnbalancedTransactionIds();
}
