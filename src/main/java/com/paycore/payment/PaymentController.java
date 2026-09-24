package com.paycore.payment;

import com.paycore.audit.AuditLog;
import com.paycore.audit.AuditLogRepository;
import com.paycore.auth.AuthenticatedMerchant;
import com.paycore.auth.CurrentMerchant;
import com.paycore.common.ApiException;
import com.paycore.common.Hashing;
import com.paycore.idempotency.IdempotentRequestHandler;
import com.paycore.ledger.LedgerEntry;
import com.paycore.ledger.LedgerEntryRepository;
import com.paycore.refund.Refund;
import com.paycore.refund.RefundRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentStateService paymentState;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final LedgerEntryRepository ledger;
    private final AuditLogRepository auditLogs;
    private final IdempotentRequestHandler idempotent;

    public PaymentController(PaymentService paymentService, PaymentStateService paymentState,
                             PaymentRepository payments, RefundRepository refunds, LedgerEntryRepository ledger,
                             AuditLogRepository auditLogs, IdempotentRequestHandler idempotent) {
        this.paymentService = paymentService;
        this.paymentState = paymentState;
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.auditLogs = auditLogs;
        this.idempotent = idempotent;
    }

    public record CreatePaymentRequest(
            @NotNull @Positive Long amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank String customerId,
            @NotBlank String paymentMethodId) {

        String hash() {
            return Hashing.sha256Hex(String.join("\n", "POST /api/v1/payments", amount.toString(),
                    currency.toUpperCase(java.util.Locale.ROOT), customerId, paymentMethodId));
        }
    }

    /**
     * 201 when the payment reached a final state, 202 while it is still PENDING (e.g. processor timeout).
     * Replays of the same Idempotency-Key return the original status and body with {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreatePaymentRequest request,
                                    @CurrentMerchant AuthenticatedMerchant merchant) {
        return idempotent.execute(merchant.id(), idempotencyKey, request.hash(),
                id -> PaymentResponse.of(paymentService.get(id)),
                () -> {
                    Payment payment = paymentService.createAndProcess(merchant.id(),
                            new CreatePaymentCommand(request.amount(), request.currency(), request.customerId(),
                                    request.paymentMethodId()),
                            idempotencyKey);
                    HttpStatus status = payment.getStatus() == PaymentStatus.PENDING ? HttpStatus.ACCEPTED
                            : HttpStatus.CREATED;
                    return ResponseEntity.status(status).body(PaymentResponse.of(payment));
                });
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable String id, @CurrentMerchant AuthenticatedMerchant merchant) {
        return PaymentResponse.of(find(id, merchant));
    }

    @GetMapping
    public List<PaymentResponse> list(@RequestParam(required = false) PaymentStatus status,
                                      @RequestParam(defaultValue = "20") int limit,
                                      @CurrentMerchant AuthenticatedMerchant merchant) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 100));
        List<Payment> result = status == null
                ? payments.findByMerchantIdOrderByCreatedAtDesc(merchant.id(), page)
                : payments.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchant.id(), status, page);
        return result.stream().map(PaymentResponse::of).toList();
    }

    @PostMapping("/{id}/cancel")
    public PaymentResponse cancel(@PathVariable String id, @CurrentMerchant AuthenticatedMerchant merchant) {
        return PaymentResponse.of(paymentState.cancel(merchant.id(), id));
    }

    public record LedgerEntryResponse(String transactionId, String refundId, String accountId, String entryType,
                                      long amount, String currency) {}

    @GetMapping("/{id}/ledger")
    public List<LedgerEntryResponse> ledger(@PathVariable String id, @CurrentMerchant AuthenticatedMerchant merchant) {
        find(id, merchant);
        return ledger.findByPaymentIdOrderByIdAsc(id).stream()
                .map((LedgerEntry e) -> new LedgerEntryResponse(e.getTransactionId(), e.getRefundId(),
                        e.getAccountId(), e.getEntryType().name(), e.getAmount(), e.getCurrency()))
                .toList();
    }

    public record AuditLogResponse(String entityType, String entityId, String action, String oldValue,
                                   String newValue, String actor, String metadata, Instant createdAt) {}

    /** The payment's full history, including its refunds. */
    @GetMapping("/{id}/audit-logs")
    public List<AuditLogResponse> auditLogs(@PathVariable String id, @CurrentMerchant AuthenticatedMerchant merchant) {
        find(id, merchant);
        List<String> entityIds = new ArrayList<>();
        entityIds.add(id);
        refunds.findByPaymentIdOrderByCreatedAtAsc(id).stream().map(Refund::getId).forEach(entityIds::add);
        return auditLogs.findByEntityIdInOrderByIdAsc(entityIds).stream()
                .map((AuditLog a) -> new AuditLogResponse(a.getEntityType(), a.getEntityId(), a.getAction(),
                        a.getOldValue(), a.getNewValue(), a.getActor(), a.getMetadata(), a.getCreatedAt()))
                .toList();
    }

    private Payment find(String id, AuthenticatedMerchant merchant) {
        return payments.findByIdAndMerchantId(id, merchant.id())
                .orElseThrow(() -> ApiException.notFound("Payment", id));
    }
}
