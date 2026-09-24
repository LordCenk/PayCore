package com.paycore.refund;

import com.paycore.common.ApiException;
import com.paycore.payment.Payment;
import com.paycore.payment.PaymentRepository;
import com.paycore.processor.PaymentProcessor;
import com.paycore.processor.PaymentProcessor.RefundRequest;
import com.paycore.processor.ProcessorTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundStateService state;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentProcessor processor;

    public RefundService(RefundStateService state, RefundRepository refunds, PaymentRepository payments,
                         PaymentProcessor processor) {
        this.state = state;
        this.refunds = refunds;
        this.payments = payments;
        this.processor = processor;
    }

    public Refund requestAndProcess(String merchantId, String paymentId, Long amount, String reason,
                                    String idempotencyKey) {
        Refund refund = state.request(merchantId, paymentId, amount, reason, idempotencyKey);
        process(refund.getId(), "merchant:" + merchantId);
        return get(refund.getId());
    }

    /** Sends the refund to the processor. Safe to repeat: the refund's reference is the processor's idempotency key. */
    public void process(String refundId, String actor) {
        Refund refund = get(refundId);
        if (refund.getStatus() != RefundStatus.PENDING) {
            return;
        }
        Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
        try {
            PaymentProcessor.RefundResult result = processor.refund(new RefundRequest(refund.getProcessorReference(),
                    payment.getProcessorReference(), refund.getAmount(), payment.getCurrency()));
            state.apply(refundId, result, actor);
        } catch (ProcessorTimeoutException e) {
            // Stays PENDING; reconciliation asks the processor later.
            log.warn("processor timeout refund={}", refundId);
        }
    }

    public Refund get(String refundId) {
        return refunds.findById(refundId).orElseThrow(() -> ApiException.notFound("Refund", refundId));
    }
}
