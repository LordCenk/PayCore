package com.paycore.payment;

import com.paycore.common.ApiException;
import com.paycore.config.PayCoreProperties;
import com.paycore.merchant.Merchant;
import com.paycore.paymentmethod.PaymentMethodRepository;
import com.paycore.processor.PaymentProcessor;
import com.paycore.processor.PaymentProcessor.ChargeRequest;
import com.paycore.processor.PaymentProcessor.ChargeResult;
import com.paycore.processor.ProcessorTimeoutException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a payment: short DB transactions around a processor call made outside any transaction.
 *
 * <pre>
 *   tx1  create payment, fraud check, PENDING, commit     (intent is durable before money moves)
 *   tx2  claim attempt, schedule retry lease, commit
 *        call processor with the payment's processor_reference
 *   tx3  apply result: SUCCESS + ledger + outbox, or FAILED; timeout leaves it PENDING
 * </pre>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentStateService state;
    private final PaymentRepository payments;
    private final PaymentMethodRepository paymentMethods;
    private final PaymentProcessor processor;
    private final PayCoreProperties properties;

    public PaymentService(PaymentStateService state, PaymentRepository payments,
                          PaymentMethodRepository paymentMethods, PaymentProcessor processor,
                          PayCoreProperties properties) {
        this.state = state;
        this.payments = payments;
        this.paymentMethods = paymentMethods;
        this.processor = processor;
        this.properties = properties;
    }

    public Payment createAndProcess(Merchant merchant, CreatePaymentCommand command, String idempotencyKey) {
        String actor = "merchant:" + merchant.getId();
        Payment payment = state.create(merchant, command, idempotencyKey, actor);
        if (payment.getStatus() == PaymentStatus.PENDING) {
            attempt(payment.getId(), false, actor);
        }
        return get(payment.getId());
    }

    /**
     * One processor attempt. Retries first ask the gateway whether it already has the charge,
     * and only re-send if it doesn't; the shared reference makes even a re-send harmless.
     */
    public void attempt(String paymentId, boolean isRetry, String actor) {
        Optional<Payment> claimed = state.claimAttempt(paymentId, isRetry);
        if (claimed.isEmpty()) {
            return;
        }
        Payment payment = claimed.get();
        try {
            if (isRetry) {
                Optional<ChargeResult> known = processor.getCharge(payment.getProcessorReference());
                if (known.isPresent()) {
                    state.applyChargeResult(paymentId, known.get(), actor);
                    return;
                }
                if (payment.getAttemptCount() > properties.retry().maxAttempts()) {
                    state.failRetriesExhausted(paymentId, actor);
                    return;
                }
            }
            String token = paymentMethods.findById(payment.getPaymentMethodId()).orElseThrow().getToken();
            ChargeResult result = processor.charge(new ChargeRequest(payment.getProcessorReference(),
                    payment.getAmount(), payment.getCurrency(), token));
            state.applyChargeResult(paymentId, result, actor);
        } catch (ProcessorTimeoutException e) {
            log.warn("processor timeout payment={} attempt={}", paymentId, payment.getAttemptCount());
            state.recordTimeout(paymentId, actor);
        }
    }

    public Payment get(String paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment", paymentId));
    }
}
