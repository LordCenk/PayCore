package com.paycore.webhook.inbound;

import com.paycore.payment.ApplyOutcome;
import com.paycore.payment.PaymentRepository;
import com.paycore.payment.PaymentStateService;
import com.paycore.processor.PaymentProcessor;
import com.paycore.processor.PaymentProcessor.ChargeResult;
import com.paycore.processor.PaymentProcessor.Outcome;
import com.paycore.processor.PaymentProcessor.RefundResult;
import com.paycore.refund.RefundRepository;
import com.paycore.refund.RefundStateService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores then processes processor webhooks. See PAYCORE_DESIGN.md, failure scenario 4.
 * The unique (provider, event_id) constraint drops redeliveries; the state machine drops
 * events that are stale or out of order.
 */
@Service
public class InboundWebhookService {

    private static final Logger log = LoggerFactory.getLogger(InboundWebhookService.class);

    private final WebhookEventRepository events;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentStateService paymentState;
    private final RefundStateService refundState;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InboundWebhookService(WebhookEventRepository events, PaymentRepository payments, RefundRepository refunds,
                                 PaymentStateService paymentState, RefundStateService refundState,
                                 PlatformTransactionManager txManager, Clock clock) {
        this.events = events;
        this.payments = payments;
        this.refunds = refunds;
        this.paymentState = paymentState;
        this.refundState = refundState;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public enum Receipt { ACCEPTED, DUPLICATE }

    public Receipt receive(String provider, ProcessorWebhook webhook, String rawPayload) {
        Integer inserted = tx.execute(s ->
                events.tryInsert(provider, webhook.id(), webhook.type(), rawPayload, clock.instant()));
        if (inserted == null || inserted == 0) {
            log.info("duplicate webhook provider={} event={}", provider, webhook.id());
            return Receipt.DUPLICATE;
        }
        process(provider, webhook);
        return Receipt.ACCEPTED;
    }

    private void process(String provider, ProcessorWebhook webhook) {
        String actor = "webhook:" + provider;
        WebhookEvent.Status status;
        String error = null;
        try {
            ApplyOutcome outcome = switch (webhook.type()) {
                case "payment.succeeded", "payment.failed" -> applyPayment(webhook, actor);
                case "refund.succeeded", "refund.failed" -> applyRefund(webhook, actor);
                default -> null;
            };
            if (outcome == null) {
                status = WebhookEvent.Status.IGNORED;
                error = "Unsupported event type";
            } else if (outcome == ApplyOutcome.APPLIED) {
                status = WebhookEvent.Status.PROCESSED;
            } else {
                status = WebhookEvent.Status.IGNORED;
                error = outcome == ApplyOutcome.ALREADY_APPLIED ? "Already applied" : "Conflicts with current state";
            }
        } catch (UnknownReferenceException e) {
            status = WebhookEvent.Status.FAILED;
            error = e.getMessage();
        } catch (RuntimeException e) {
            log.error("webhook processing failed event={}", webhook.id(), e);
            status = WebhookEvent.Status.FAILED;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        WebhookEvent.Status finalStatus = status;
        String finalError = error;
        tx.executeWithoutResult(s -> events.findByProviderAndEventId(provider, webhook.id())
                .ifPresent(e -> e.finish(finalStatus, finalError, clock.instant())));
    }

    private ApplyOutcome applyPayment(ProcessorWebhook webhook, String actor) {
        String reference = reference(webhook);
        String paymentId = payments.findByProcessorReference(reference)
                .orElseThrow(() -> new UnknownReferenceException(reference)).getId();
        Outcome outcome = webhook.type().equals("payment.succeeded") ? Outcome.SUCCEEDED : Outcome.DECLINED;
        return paymentState.applyChargeResult(paymentId,
                new ChargeResult(outcome, webhook.data().providerId(), webhook.data().declineCode(),
                        webhook.data().message()), actor);
    }

    private ApplyOutcome applyRefund(ProcessorWebhook webhook, String actor) {
        String reference = reference(webhook);
        String refundId = refunds.findByProcessorReference(reference)
                .orElseThrow(() -> new UnknownReferenceException(reference)).getId();
        PaymentProcessor.Outcome outcome = webhook.type().equals("refund.succeeded") ? Outcome.SUCCEEDED
                : Outcome.DECLINED;
        return refundState.apply(refundId, new RefundResult(outcome, webhook.data().providerId(),
                webhook.data().declineCode(), webhook.data().message()), actor);
    }

    private static String reference(ProcessorWebhook webhook) {
        if (webhook.data() == null || webhook.data().reference() == null) {
            throw new UnknownReferenceException(null);
        }
        return webhook.data().reference();
    }

    static class UnknownReferenceException extends RuntimeException {
        UnknownReferenceException(String reference) {
            super("Unknown processor reference: " + reference);
        }
    }
}
