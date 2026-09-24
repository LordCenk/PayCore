package com.paycore.merchant;

import com.paycore.audit.AuditService;
import com.paycore.common.ApiException;
import com.paycore.common.Hashing;
import com.paycore.common.Ids;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final AuditService audit;
    private final Clock clock;

    public MerchantService(MerchantRepository merchants, AuditService audit, Clock clock) {
        this.merchants = merchants;
        this.audit = audit;
        this.clock = clock;
    }

    /** The raw API key and webhook secret are returned once and never stored in plain text (API key). */
    public record Registration(Merchant merchant, String apiKey) {}

    @Transactional
    public Registration register(String name, String email, String webhookUrl) {
        if (merchants.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "MERCHANT_EXISTS", "A merchant with this email already exists");
        }
        String apiKey = "sk_test_" + Ids.random(32);
        String webhookSecret = "whsec_" + Ids.random(32);
        Instant now = clock.instant();
        Merchant merchant = new Merchant(Ids.newId("merchant"), name, email, Hashing.sha256Hex(apiKey),
                webhookUrl, webhookSecret, now);
        merchants.save(merchant);
        audit.record("MERCHANT", merchant.getId(), "CREATED", null, "ACTIVE", "system:api", null);
        return new Registration(merchant, apiKey);
    }

    @Transactional(readOnly = true)
    public Merchant authenticate(String apiKey) {
        return merchants.findByApiKeyHash(Hashing.sha256Hex(apiKey))
                .filter(Merchant::isActive)
                .orElse(null);
    }
}
