package com.paycore.refund;

import com.paycore.audit.AuditService;
import com.paycore.common.ApiException;
import com.paycore.common.Ids;
import com.paycore.idempotency.IdempotencyService;
import com.paycore.ledger.LedgerService;
import com.paycore.outbox.OutboxService;
import com.paycore.payment.ApplyOutcome;
import com.paycore.payment.Payment;
import com.paycore.payment.PaymentRepository;
import com.paycore.payment.PaymentStatus;
import com.paycore.processor.PaymentProcessor;
import com.paycore.processor.PaymentProcessor.RefundResult;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund state changes. Lock order is always payment, then refund, so refund and payment
 * updates from different threads can't deadlock. See PAYCORE_DESIGN.md, failure scenario 5.
 */
@Service
public class RefundStateService {

    public static final String REQUESTED = "RefundRequested";
    public static final String SUCCEEDED = "RefundSucceeded";
    public static final String FAILED = "RefundFailed";

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final LedgerService ledger;
    private final AuditService audit;
    private final OutboxService outbox;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public RefundStateService(PaymentRepository payments, RefundRepository refunds, LedgerService ledger,
                              AuditService audit, OutboxService outbox, IdempotencyService idempotency, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.audit = audit;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional
    public Refund request(String merchantId, String paymentId, Long requestedAmount, String reason,
                          String idempotencyKey) {
        // The row lock serializes this with the processor result for the same payment.
        Payment payment = payments.findByIdForUpdate(paymentId)
                .filter(p -> p.getMerchantId().equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                    "Payment " + paymentId + " is " + payment.getStatus() + "; only SUCCESS payments can be refunded");
        }
        long amount = requestedAmount == null ? payment.getAmount() : requestedAmount;
        if (amount > payment.getAmount()) {
            throw ApiException.badRequest("REFUND_EXCEEDS_PAYMENT", "Refund amount exceeds the payment amount");
        }
        if (amount != payment.getAmount()) {
            throw ApiException.badRequest("PARTIAL_REFUND_NOT_SUPPORTED", "V1 supports full refunds only");
        }

        Instant now = clock.instant();
        Refund refund = new Refund(Ids.newId("re"), paymentId, amount, reason, Ids.newId("rref"), now);
        refunds.saveAndFlush(refund);
        if (idempotencyKey != null) {
            idempotency.attachResource(merchantId, idempotencyKey, refund.getId());
        }
        String actor = "merchant:" + merchantId;
        PaymentStatus previous = payment.transitionTo(PaymentStatus.REFUND_PENDING, now);
        audit.record("PAYMENT", paymentId, "STATUS_CHANGED", previous.name(), PaymentStatus.REFUND_PENDING.name(),
                actor, Map.of("refundId", refund.getId()));
        audit.record("REFUND", refund.getId(), "CREATED", null, RefundStatus.PENDING.name(), actor,
                Map.of("amount", amount));
        outbox.enqueue("REFUND", refund.getId(), payment.getId(), payment.getMerchantId(), REQUESTED, data(payment, refund));
        return refund;
    }

    @Transactional
    public ApplyOutcome apply(String refundId, RefundResult result, String actor) {
        String paymentId = refunds.findById(refundId)
                .orElseThrow(() -> ApiException.notFound("Refund", refundId)).getPaymentId();
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        Refund refund = refunds.findByIdForUpdate(refundId).orElseThrow();
        boolean succeeded = result.outcome() == PaymentProcessor.Outcome.SUCCEEDED;

        if (refund.getStatus() != RefundStatus.PENDING) {
            RefundStatus expected = succeeded ? RefundStatus.SUCCEEDED : RefundStatus.FAILED;
            if (refund.getStatus() == expected) {
                return ApplyOutcome.ALREADY_APPLIED;
            }
            audit.record("REFUND", refundId, "PROCESSOR_RESULT_CONFLICT", refund.getStatus().name(),
                    result.outcome().name(), actor, null);
            return ApplyOutcome.CONFLICT;
        }

        Instant now = clock.instant();
        if (succeeded) {
            refund.succeeded(result.providerRefundId(), now);
            payment.transitionTo(PaymentStatus.REFUNDED, now);
            String tx = ledger.recordRefund(payment, refund);
            audit.record("REFUND", refundId, "STATUS_CHANGED", "PENDING", "SUCCEEDED", actor, Map.of("ledgerTx", tx));
            audit.record("PAYMENT", paymentId, "STATUS_CHANGED", "REFUND_PENDING", "REFUNDED", actor,
                    Map.of("refundId", refundId));
            outbox.enqueue("REFUND", refundId, payment.getId(), payment.getMerchantId(), SUCCEEDED, data(payment, refund));
        } else {
            refund.failed(result.declineCode(), result.message(), now);
            // The customer is still charged, so the payment goes back to SUCCESS and can be refunded again.
            payment.transitionTo(PaymentStatus.SUCCESS, now);
            audit.record("REFUND", refundId, "STATUS_CHANGED", "PENDING", "FAILED", actor,
                    Map.of("failureCode", String.valueOf(result.declineCode())));
            audit.record("PAYMENT", paymentId, "STATUS_CHANGED", "REFUND_PENDING", "SUCCESS", actor,
                    Map.of("refundId", refundId));
            outbox.enqueue("REFUND", refundId, payment.getId(), payment.getMerchantId(), FAILED, data(payment, refund));
        }
        return ApplyOutcome.APPLIED;
    }

    private static Map<String, Object> data(Payment payment, Refund refund) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", refund.getId());
        data.put("paymentId", payment.getId());
        data.put("merchantId", payment.getMerchantId());
        data.put("customerId", payment.getCustomerId());
        data.put("status", refund.getStatus().name());
        data.put("amount", refund.getAmount());
        data.put("currency", payment.getCurrency());
        return data;
    }
}
