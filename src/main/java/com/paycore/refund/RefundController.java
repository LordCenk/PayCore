package com.paycore.refund;

import com.paycore.auth.CurrentMerchant;
import com.paycore.common.ApiException;
import com.paycore.common.Hashing;
import com.paycore.idempotency.IdempotentRequestHandler;
import com.paycore.merchant.Merchant;
import com.paycore.payment.PaymentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

    private final RefundService refundService;
    private final PaymentRepository payments;
    private final IdempotentRequestHandler idempotent;

    public RefundController(RefundService refundService, PaymentRepository payments,
                            IdempotentRequestHandler idempotent) {
        this.refundService = refundService;
        this.payments = payments;
        this.idempotent = idempotent;
    }

    /** {@code amount} is optional and defaults to the full payment amount. */
    public record CreateRefundRequest(@Positive Long amount, @Size(max = 500) String reason) {}

    @PostMapping("/api/v1/payments/{paymentId}/refund")
    public ResponseEntity<?> create(@PathVariable String paymentId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody(required = false) CreateRefundRequest body,
                                    @CurrentMerchant Merchant merchant) {
        CreateRefundRequest request = body == null ? new CreateRefundRequest(null, null) : body;
        String hash = Hashing.sha256Hex(String.join("\n", "POST /api/v1/payments/" + paymentId + "/refund",
                Objects.toString(request.amount()), Objects.toString(request.reason())));
        return idempotent.execute(merchant.getId(), idempotencyKey, hash,
                id -> RefundResponse.of(refundService.get(id)),
                () -> {
                    Refund refund = refundService.requestAndProcess(merchant, paymentId, request.amount(),
                            request.reason(), idempotencyKey);
                    HttpStatus status = refund.getStatus() == RefundStatus.PENDING ? HttpStatus.ACCEPTED
                            : HttpStatus.CREATED;
                    return ResponseEntity.status(status).body(RefundResponse.of(refund));
                });
    }

    @GetMapping("/api/v1/refunds/{id}")
    public RefundResponse get(@PathVariable String id, @CurrentMerchant Merchant merchant) {
        Refund refund = refundService.get(id);
        payments.findByIdAndMerchantId(refund.getPaymentId(), merchant.getId())
                .orElseThrow(() -> ApiException.notFound("Refund", id));
        return RefundResponse.of(refund);
    }
}
