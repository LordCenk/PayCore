package com.paycore.merchant;

import com.paycore.auth.AuthenticatedMerchant;
import com.paycore.auth.CurrentMerchant;
import com.paycore.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService service;

    public MerchantController(MerchantService service) {
        this.service = service;
    }

    public record CreateMerchantRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email String email,
            @Pattern(regexp = "https?://.+", message = "must be an http(s) URL") String webhookUrl) {}

    public record MerchantResponse(String id, String name, String email, String status, String webhookUrl,
                                   Instant createdAt) {
        static MerchantResponse of(AuthenticatedMerchant m) {
            return new MerchantResponse(m.id(), m.name(), m.email(), m.status(), m.webhookUrl(), m.createdAt());
        }
    }

    public record CreateMerchantResponse(MerchantResponse merchant, String apiKey, String webhookSecret) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateMerchantResponse create(@Valid @RequestBody CreateMerchantRequest request) {
        MerchantService.Registration r = service.register(request.name(), request.email(), request.webhookUrl());
        return new CreateMerchantResponse(MerchantResponse.of(AuthenticatedMerchant.of(r.merchant())), r.apiKey(),
                r.merchant().getWebhookSecret());
    }

    /** A merchant can only read its own record. */
    @GetMapping("/{id}")
    public MerchantResponse get(@PathVariable String id, @CurrentMerchant AuthenticatedMerchant merchant) {
        if (!merchant.id().equals(id)) {
            throw ApiException.notFound("Merchant", id);
        }
        return MerchantResponse.of(merchant);
    }
}
