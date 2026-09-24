package com.paycore.reconciliation;

import com.paycore.audit.AuditService;
import com.paycore.config.PayCoreProperties;
import com.paycore.ledger.LedgerEntryRepository;
import com.paycore.payment.Payment;
import com.paycore.payment.PaymentRepository;
import com.paycore.payment.PaymentStatus;
import com.paycore.processor.PaymentProcessor;
import com.paycore.processor.PaymentProcessor.ChargeResult;
import com.paycore.processor.PaymentProcessor.Outcome;
import com.paycore.processor.ProcessorTimeoutException;
import com.paycore.refund.Refund;
import com.paycore.refund.RefundRepository;
import com.paycore.refund.RefundService;
import com.paycore.refund.RefundStateService;
import com.paycore.refund.RefundStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compares PayCore's records with the processor's and checks the ledger's invariants.
 * Stuck PENDING payments are handled by the retry job; this resolves stuck refunds and reports
 * anything that disagrees. Mismatches are audited, never silently "fixed".
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String ACTOR = "system:reconciler";
    private static final Duration LOOKBACK = Duration.ofHours(24);
    private static final Set<PaymentStatus> CHARGED =
            EnumSet.of(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final LedgerEntryRepository ledger;
    private final RefundService refundService;
    private final RefundStateService refundState;
    private final PaymentProcessor processor;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Duration staleAfter;
    private final Clock clock;

    public ReconciliationService(PaymentRepository payments, RefundRepository refunds, LedgerEntryRepository ledger,
                                 RefundService refundService, RefundStateService refundState,
                                 PaymentProcessor processor, AuditService audit, PlatformTransactionManager txManager,
                                 PayCoreProperties properties, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.refundService = refundService;
        this.refundState = refundState;
        this.processor = processor;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.staleAfter = properties.jobs().reconciliationStaleAfter();
        this.clock = clock;
    }

    public ReconciliationReport run() {
        Instant now = clock.instant();
        int[] refundCounts = resolveStuckRefunds(now.minus(staleAfter));

        List<String> mismatches = new ArrayList<>();
        List<Payment> recent = payments.findByStatusInAndUpdatedAtAfter(
                EnumSet.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.REFUND_PENDING,
                        PaymentStatus.REFUNDED), now.minus(LOOKBACK));
        for (Payment p : recent) {
            compareWithProcessor(p).ifPresent(mismatches::add);
        }

        List<String> missingPaymentLedger = recent.stream()
                .filter(p -> CHARGED.contains(p.getStatus()))
                .filter(p -> !ledger.existsByPaymentIdAndRefundIdIsNull(p.getId()))
                .map(Payment::getId)
                .toList();
        List<String> missingRefundLedger = refunds.findByStatus(RefundStatus.SUCCEEDED).stream()
                .filter(r -> !ledger.existsByRefundId(r.getId()))
                .map(Refund::getId)
                .toList();
        List<String> unbalanced = ledger.findUnbalancedTransactionIds();

        ReconciliationReport report = new ReconciliationReport(now, recent.size(), refundCounts[0], refundCounts[1],
                mismatches, missingPaymentLedger, missingRefundLedger, unbalanced);
        if (!report.clean()) {
            log.error("reconciliation found problems: {}", report);
            tx.executeWithoutResult(s -> audit.record("SYSTEM", "reconciliation", "RECONCILIATION_FAILED", null, null,
                    ACTOR, Map.of("mismatches", mismatches, "paymentsMissingLedger", missingPaymentLedger,
                            "refundsMissingLedger", missingRefundLedger, "unbalancedTransactions", unbalanced)));
        }
        return report;
    }

    /** PENDING refunds whose processor call timed out or whose process crashed: ask the processor, else resend. */
    private int[] resolveStuckRefunds(Instant staleBefore) {
        int resolved = 0;
        int resent = 0;
        for (Refund refund : refunds.findByStatusAndUpdatedAtBefore(RefundStatus.PENDING, staleBefore)) {
            try {
                Optional<PaymentProcessor.RefundResult> known = processor.getRefund(refund.getProcessorReference());
                if (known.isPresent()) {
                    refundState.apply(refund.getId(), known.get(), ACTOR);
                    resolved++;
                } else {
                    refundService.process(refund.getId(), ACTOR);
                    resent++;
                }
            } catch (ProcessorTimeoutException e) {
                log.warn("processor timeout while reconciling refund={}", refund.getId());
            } catch (RuntimeException e) {
                log.error("failed to reconcile refund={}", refund.getId(), e);
            }
        }
        return new int[] {resolved, resent};
    }

    private Optional<String> compareWithProcessor(Payment p) {
        if ("FRAUD_SUSPECTED".equals(p.getFailureCode())) {
            return Optional.empty(); // never sent to the processor
        }
        Optional<ChargeResult> charge;
        try {
            charge = processor.getCharge(p.getProcessorReference());
        } catch (ProcessorTimeoutException e) {
            return Optional.empty(); // try again next run
        }
        boolean processorCharged = charge.map(c -> c.outcome() == Outcome.SUCCEEDED).orElse(false);
        boolean weCharged = CHARGED.contains(p.getStatus());
        if (processorCharged == weCharged) {
            return Optional.empty();
        }
        String message = p.getId() + ": PayCore=" + p.getStatus() + " processor="
                + charge.map(c -> c.outcome().name()).orElse("NO_RECORD");
        tx.executeWithoutResult(s -> audit.record("PAYMENT", p.getId(), "RECONCILIATION_MISMATCH",
                p.getStatus().name(), charge.map(c -> c.outcome().name()).orElse("NO_RECORD"), ACTOR, null));
        return Optional.of(message);
    }
}
