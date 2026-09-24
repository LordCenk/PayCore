package com.paycore.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only double-entry row. Corrections are new entries, never updates. */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    public enum Type { DEBIT, CREDIT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "refund_id")
    private String refundId;

    @Column(name = "account_id")
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type")
    private Type entryType;

    private long amount;
    private String currency;

    @Column(name = "created_at")
    private Instant createdAt;

    protected LedgerEntry() {}

    public LedgerEntry(String transactionId, String paymentId, String refundId, String accountId, Type entryType,
                       long amount, String currency, Instant createdAt) {
        this.transactionId = transactionId;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public String getTransactionId() { return transactionId; }
    public String getPaymentId() { return paymentId; }
    public String getRefundId() { return refundId; }
    public String getAccountId() { return accountId; }
    public Type getEntryType() { return entryType; }
    public long getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
