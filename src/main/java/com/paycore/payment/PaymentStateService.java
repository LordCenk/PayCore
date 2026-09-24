package com.paycore.payment;

import com.paycore.audit.AuditService;
import com.paycore.common.ApiException;
import com.paycore.common.Ids;
import com.paycore.config.PayCoreProperties;
import com.paycore.customer.CustomerRepository;
import com.paycore.fraud.FraudContext;
import com.paycore.fraud.FraudDecision;
import com.paycore.fraud.FraudResult;
import com.paycore.fraud.FraudService;
import com.paycore.idempotency.IdempotencyService;
import com.paycore.ledger.LedgerService;
import com.paycore.merchant.Merchant;
import com.paycore.outbox.OutboxService;
import com.paycore.paymentmethod.PaymentMethod;
import com.paycore.paymentmethod.PaymentMethodRepository;
import com.paycore.processor.PaymentProcessor;
import com.paycore.processor.PaymentProcessor.ChargeResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every payment state change, each in its own short DB transaction. Processor calls never happen
 * in here: holding a DB transaction open across a network call is how systems fall over.
 *
 * <p>Each method locks the payment row first, then writes status + ledger + audit + outbox together,
 * so a crash leaves all of them or none of them.
 */
@Service
public class PaymentStateService {

    private final PaymentRepository payments;
    private final CustomerRepository customers;
    private final PaymentMethodRepository paymentMethods;
    private final FraudService fraud;
    private final LedgerService ledger;
    private final AuditService audit;
    private final OutboxService outbox;
    private final IdempotencyService idempotency;
    private final PaymentProcessor processor;
    private final PayCoreProperties properties;
    private final Clock clock;

    public PaymentStateService(PaymentRepository payments, CustomerRepository customers,
                               PaymentMethodRepository paymentMethods, FraudService fraud, LedgerService ledger,
                               AuditService audit, OutboxService outbox, IdempotencyService idempotency,
                               PaymentProcessor processor, PayCoreProperties properties, Clock clock) {
        this.payments = payments;
        this.customers = customers;
        this.paymentMethods = paymentMethods;
        this.fraud = fraud;
        this.ledger = ledger;
        this.audit = audit;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    /** Validates, stores the payment, runs fraud rules, and leaves it PENDING (or FAILED if blocked). */
    @Transactional
    public Payment create(Merchant merchant, CreatePaymentCommand command, String idempotencyKey, String actor) {
        String currency = command.currency().toUpperCase();
        if (!properties.supportedCurrencies().contains(currency)) {
            throw ApiException.badRequest("UNSUPPORTED_CURRENCY", "Currency " + command.currency() + " is not supported");
        }
        if (command.amount() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "amount must be positive");
        }
        customers.findByIdAndMerchantId(command.customerId(), merchant.getId())
                .orElseThrow(() -> ApiException.notFound("Customer", command.customerId()));
        PaymentMethod method = paymentMethods.findById(command.paymentMethodId())
                .filter(pm -> pm.getCustomerId().equals(command.customerId()))
                .orElseThrow(() -> ApiException.notFound("Payment method", command.paymentMethodId()));
        if (!method.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_INACTIVE", "Payment method is not active");
        }

        // Evaluate before inserting so the velocity rule counts only earlier payments.
        FraudResult fraudResult = fraud.evaluate(new FraudContext(merchant.getId(), command.customerId(),
                method.getToken(), command.amount(), currency));

        Instant now = clock.instant();
        Payment payment = new Payment(Ids.newId("pay"), merchant.getId(), command.customerId(),
                command.paymentMethodId(), command.amount(), currency, processor.name(), Ids.newId("ref"),
                idempotencyKey, now);
        payments.save(payment);
        if (idempotencyKey != null) {
            idempotency.attachResource(merchant.getId(), idempotencyKey, payment.getId());
        }
        audit.record("PAYMENT", payment.getId(), "CREATED", null, PaymentStatus.CREATED.name(), actor,
                Map.of("amount", command.amount(), "currency", currency));
        outbox.enqueue("PAYMENT", payment.getId(), payment.getId(), merchant.getId(), PaymentEvents.CREATED, PaymentEvents.data(payment));

        for (FraudDecision flag : fraudResult.flags()) {
            audit.record("PAYMENT", payment.getId(), "FRAUD_FLAGGED", null, null, "system:fraud",
                    Map.of("rule", flag.rule(), "reason", flag.reason()));
        }

        Optional<FraudDecision> block = fraudResult.block();
        if (block.isPresent()) {
            payment.recordFailure("FRAUD_SUSPECTED", block.get().reason());
            changeStatus(payment, PaymentStatus.FAILED, "system:fraud", Map.of("rule", block.get().rule()));
            outbox.enqueue("PAYMENT", payment.getId(), payment.getId(), merchant.getId(), PaymentEvents.FAILED, PaymentEvents.data(payment));
            return payment;
        }

        changeStatus(payment, PaymentStatus.PENDING, actor, null);
        // Safety net: if this process dies before the first processor call, the retry job picks it up.
        payment.scheduleRetry(now.plus(properties.jobs().reconciliationStaleAfter()));
        return payment;
    }

    /**
     * Claims the next processor attempt. Returns empty if the payment is no longer PENDING, or
     * (for the retry job) not yet due. Pushing next_retry_at forward is the lease: another instance
     * running the retry job will skip this payment until the lease expires.
     */
    @Transactional
    public Optional<Payment> claimAttempt(String paymentId, boolean requireDue) {
        Payment payment = lock(paymentId);
        Instant now = clock.instant();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return Optional.empty();
        }
        if (requireDue && (payment.getNextRetryAt() == null || payment.getNextRetryAt().isAfter(now))) {
            return Optional.empty();
        }
        payment.startAttempt(now, now.plus(backoff(payment.getAttemptCount() + 1)));
        return Optional.of(payment);
    }

    @Transactional
    public ApplyOutcome applyChargeResult(String paymentId, ChargeResult result, String actor) {
        Payment payment = lock(paymentId);
        boolean succeeded = result.outcome() == PaymentProcessor.Outcome.SUCCEEDED;
        PaymentStatus current = payment.getStatus();

        if (current != PaymentStatus.PENDING) {
            boolean alreadyApplied = succeeded
                    ? current == PaymentStatus.SUCCESS || current == PaymentStatus.REFUND_PENDING
                            || current == PaymentStatus.REFUNDED
                    : current == PaymentStatus.FAILED;
            if (alreadyApplied) {
                return ApplyOutcome.ALREADY_APPLIED;
            }
            audit.record("PAYMENT", paymentId, "PROCESSOR_RESULT_CONFLICT", current.name(), result.outcome().name(),
                    actor, Map.of("reason", "processor result contradicts current state"));
            return ApplyOutcome.CONFLICT;
        }

        if (succeeded) {
            payment.succeeded(result.providerPaymentId());
            changeStatus(payment, PaymentStatus.SUCCESS, actor, Map.of("attempt", payment.getAttemptCount()));
            String tx = ledger.recordPayment(payment);
            audit.record("PAYMENT", paymentId, "LEDGER_POSTED", null, tx, actor, null);
            outbox.enqueue("PAYMENT", paymentId, paymentId, payment.getMerchantId(), PaymentEvents.SUCCEEDED,
                    PaymentEvents.data(payment));
        } else {
            payment.recordFailure(result.declineCode(), result.message());
            payment.stopRetrying();
            changeStatus(payment, PaymentStatus.FAILED, actor, Map.of("failureCode", String.valueOf(result.declineCode())));
            outbox.enqueue("PAYMENT", paymentId, paymentId, payment.getMerchantId(), PaymentEvents.FAILED,
                    PaymentEvents.data(payment));
        }
        return ApplyOutcome.APPLIED;
    }

    /** Outcome unknown: stay PENDING. The retry already scheduled by {@link #claimAttempt} will ask the gateway. */
    @Transactional
    public void recordTimeout(String paymentId, String actor) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        payment.recordFailure("PROCESSOR_TIMEOUT", "Processor did not respond; outcome unknown, will retry");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("attempt", payment.getAttemptCount());
        meta.put("nextRetryAt", String.valueOf(payment.getNextRetryAt()));
        audit.record("PAYMENT", paymentId, "PROCESSOR_TIMEOUT", null, null, actor, meta);
    }

    /** Only called after the gateway confirmed it has no record of the charge. */
    @Transactional
    public ApplyOutcome failRetriesExhausted(String paymentId, String actor) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return ApplyOutcome.ALREADY_APPLIED;
        }
        payment.recordFailure("RETRIES_EXHAUSTED",
                "No processor response after " + (payment.getAttemptCount() - 1) + " attempts");
        payment.stopRetrying();
        changeStatus(payment, PaymentStatus.FAILED, actor, null);
        outbox.enqueue("PAYMENT", paymentId, paymentId, payment.getMerchantId(), PaymentEvents.FAILED, PaymentEvents.data(payment));
        return ApplyOutcome.APPLIED;
    }

    @Transactional
    public Payment cancel(Merchant merchant, String paymentId) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .filter(p -> p.getMerchantId().equals(merchant.getId()))
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
        payment.stopRetrying();
        changeStatus(payment, PaymentStatus.CANCELLED, "merchant:" + merchant.getId(), null);
        outbox.enqueue("PAYMENT", paymentId, paymentId, payment.getMerchantId(), PaymentEvents.CANCELLED,
                PaymentEvents.data(payment));
        return payment;
    }

    private Payment lock(String paymentId) {
        return payments.findByIdForUpdate(paymentId).orElseThrow(() -> ApiException.notFound("Payment", paymentId));
    }

    private void changeStatus(Payment payment, PaymentStatus next, String actor, Map<String, ?> metadata) {
        PaymentStatus previous = payment.transitionTo(next, clock.instant());
        audit.record("PAYMENT", payment.getId(), "STATUS_CHANGED", previous.name(), next.name(), actor, metadata);
    }

    /** Exponential backoff with up to 20% jitter, so retries from many payments don't arrive in lockstep. */
    Duration backoff(int attempt) {
        PayCoreProperties.Retry retry = properties.retry();
        long base = retry.initialBackoff().toMillis() << Math.min(attempt - 1, 20);
        long capped = Math.min(base, retry.maxBackoff().toMillis());
        long jitter = (long) (capped * 0.2 * ThreadLocalRandom.current().nextDouble());
        return Duration.ofMillis(capped + jitter);
    }
}
