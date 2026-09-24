package com.paycore.paymentmethod;

import com.paycore.auth.AuthenticatedMerchant;
import com.paycore.auth.CurrentMerchant;
import com.paycore.common.ApiException;
import com.paycore.common.Ids;
import com.paycore.customer.CustomerRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-methods")
public class PaymentMethodController {

    private final PaymentMethodRepository paymentMethods;
    private final CustomerRepository customers;
    private final Clock clock;

    public PaymentMethodController(PaymentMethodRepository paymentMethods, CustomerRepository customers,
                                   Clock clock) {
        this.paymentMethods = paymentMethods;
        this.customers = customers;
        this.clock = clock;
    }

    /**
     * The client sends a token obtained from the (mock) gateway, never a card number.
     * Test tokens such as {@code tok_success} or {@code tok_decline} force a processor outcome.
     */
    public record CreatePaymentMethodRequest(
            @NotBlank String customerId,
            @NotNull PaymentMethod.Type type,
            @NotBlank @Pattern(regexp = "tok_[A-Za-z0-9_]+", message = "must be a gateway token (tok_...)") String token,
            @NotBlank @Pattern(regexp = "\\d{4}", message = "must be 4 digits") String lastFour) {}

    public record PaymentMethodResponse(String id, String customerId, String type, String provider,
                                        String lastFour, String status, Instant createdAt) {
        static PaymentMethodResponse of(PaymentMethod pm) {
            return new PaymentMethodResponse(pm.getId(), pm.getCustomerId(), pm.getType().name(), pm.getProvider(),
                    pm.getLastFour(), pm.getStatus().name(), pm.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PaymentMethodResponse create(@Valid @RequestBody CreatePaymentMethodRequest request,
                                        @CurrentMerchant AuthenticatedMerchant merchant) {
        customers.findByIdAndMerchantId(request.customerId(), merchant.id())
                .orElseThrow(() -> ApiException.notFound("Customer", request.customerId()));
        PaymentMethod pm = new PaymentMethod(Ids.newId("pm"), request.customerId(), request.type(),
                "MOCK_GATEWAY", request.token(), request.lastFour(), clock.instant());
        return PaymentMethodResponse.of(paymentMethods.save(pm));
    }

    @GetMapping("/{id}")
    public PaymentMethodResponse get(@PathVariable String id, @CurrentMerchant AuthenticatedMerchant merchant) {
        PaymentMethod pm = paymentMethods.findById(id).orElseThrow(() -> ApiException.notFound("Payment method", id));
        customers.findByIdAndMerchantId(pm.getCustomerId(), merchant.id())
                .orElseThrow(() -> ApiException.notFound("Payment method", id));
        return PaymentMethodResponse.of(pm);
    }
}
