package com.paycore.ledger;

import com.paycore.common.Ids;
import com.paycore.payment.Payment;
import com.paycore.refund.Refund;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes balanced double-entry transactions.
 * A payment debits the customer and credits the merchant; a refund reverses that.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository entries;
    private final Clock clock;

    public LedgerService(LedgerEntryRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    public static String customerAccount(String customerId) {
        return "CUSTOMER:" + customerId;
    }

    public static String merchantAccount(String merchantId) {
        return "MERCHANT:" + merchantId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String recordPayment(Payment payment) {
        String tx = Ids.newId("tx");
        Instant now = clock.instant();
        save(List.of(
                new LedgerEntry(tx, payment.getId(), null, customerAccount(payment.getCustomerId()),
                        LedgerEntry.Type.DEBIT, payment.getAmount(), payment.getCurrency(), now),
                new LedgerEntry(tx, payment.getId(), null, merchantAccount(payment.getMerchantId()),
                        LedgerEntry.Type.CREDIT, payment.getAmount(), payment.getCurrency(), now)));
        return tx;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String recordRefund(Payment payment, Refund refund) {
        String tx = Ids.newId("tx");
        Instant now = clock.instant();
        save(List.of(
                new LedgerEntry(tx, payment.getId(), refund.getId(), merchantAccount(payment.getMerchantId()),
                        LedgerEntry.Type.DEBIT, refund.getAmount(), payment.getCurrency(), now),
                new LedgerEntry(tx, payment.getId(), refund.getId(), customerAccount(payment.getCustomerId()),
                        LedgerEntry.Type.CREDIT, refund.getAmount(), payment.getCurrency(), now)));
        return tx;
    }

    private void save(List<LedgerEntry> transaction) {
        assertBalanced(transaction);
        entries.saveAll(transaction);
    }

    /** The core accounting invariant: total debits equal total credits. */
    static void assertBalanced(List<LedgerEntry> transaction) {
        long debits = 0;
        long credits = 0;
        for (LedgerEntry e : transaction) {
            if (e.getAmount() <= 0) {
                throw new IllegalStateException("Ledger amounts must be positive");
            }
            if (e.getEntryType() == LedgerEntry.Type.DEBIT) {
                debits = Math.addExact(debits, e.getAmount());
            } else {
                credits = Math.addExact(credits, e.getAmount());
            }
        }
        if (debits != credits) {
            throw new IllegalStateException("Unbalanced ledger transaction: debits=" + debits + " credits=" + credits);
        }
    }
}
