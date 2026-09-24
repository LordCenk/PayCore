package com.paycore.customer;

import com.paycore.auth.CurrentMerchant;
import com.paycore.common.ApiException;
import com.paycore.common.Ids;
import com.paycore.merchant.Merchant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerRepository customers;
    private final Clock clock;

    public CustomerController(CustomerRepository customers, Clock clock) {
        this.customers = customers;
        this.clock = clock;
    }

    public record CreateCustomerRequest(@NotBlank @Size(max = 200) String name, @NotBlank @Email String email) {}

    public record CustomerResponse(String id, String name, String email, Instant createdAt) {
        static CustomerResponse of(Customer c) {
            return new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CustomerResponse create(@Valid @RequestBody CreateCustomerRequest request,
                                   @CurrentMerchant Merchant merchant) {
        Customer customer = new Customer(Ids.newId("cust"), merchant.getId(), request.name(), request.email(),
                clock.instant());
        return CustomerResponse.of(customers.save(customer));
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable String id, @CurrentMerchant Merchant merchant) {
        return customers.findByIdAndMerchantId(id, merchant.getId())
                .map(CustomerResponse::of)
                .orElseThrow(() -> ApiException.notFound("Customer", id));
    }
}
