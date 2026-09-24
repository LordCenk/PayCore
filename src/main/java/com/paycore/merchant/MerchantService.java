package com.paycore.merchant;

import com.paycore.audit.AuditService;
import com.paycore.auth.AuthenticatedMerchant;
import com.paycore.common.ApiException;
import com.paycore.common.Hashing;
import com.paycore.common.Ids;
import com.paycore.webhook.outbound.WebhookTargetPolicy;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final AuditService audit;
    private final MerchantAuthCache cache;
    private final WebhookTargetPolicy webhookPolicy;
    private final Clock clock;

    public MerchantService(MerchantRepository merchants, AuditService audit, MerchantAuthCache cache,
                           WebhookTargetPolicy webhookPolicy, Clock clock) {
        this.merchants = merchants;
        this.audit = audit;
        this.cache = cache;
        this.webhookPolicy = webhookPolicy;
        this.clock = clock;
    }

    /** The raw API key and webhook secret are returned once and never stored in plain text (API key). */
    public record Registration(Merchant merchant, String apiKey) {}

    private static ApiException merchantExists() {
        return new ApiException(HttpStatus.CONFLICT, "MERCHANT_EXISTS", "A merchant with this email already exists");
    }

    @Transactional
    public Registration register(String name, String email, String webhookUrl) {
        if (webhookUrl != null) {
            webhookPolicy.validateForRegistration(webhookUrl);
        }
        if (merchants.existsByEmail(email)) {
            throw merchantExists();
        }
        String apiKey = "sk_test_" + Ids.random(32);
        String webhookSecret = "whsec_" + Ids.random(32);
        Instant now = clock.instant();
        Merchant merchant = new Merchant(Ids.newId("merchant"), name, email, Hashing.sha256Hex(apiKey),
                webhookUrl, webhookSecret, now);
        try {
            merchants.saveAndFlush(merchant);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent registration for the same email: the unique constraint decides.
            throw merchantExists();
        }
        audit.record("MERCHANT", merchant.getId(), "CREATED", null, "ACTIVE", "system:api", null);
        return new Registration(merchant, apiKey);
    }

    /** Returns the active merchant owning this API key, or null. Only active merchants are cached. */
    /**
     * Deliberately not @Transactional: a transaction would take a database connection on every request,
     * even when the merchant comes from the Redis cache. The repository call has its own transaction.
     */
    public AuthenticatedMerchant authenticate(String apiKey) {
        String hash = Hashing.sha256Hex(apiKey);
        return cache.get(hash).orElseGet(() -> {
            AuthenticatedMerchant merchant = merchants.findByApiKeyHash(hash)
                    .filter(Merchant::isActive)
                    .map(AuthenticatedMerchant::of)
                    .orElse(null);
            if (merchant != null) {
                cache.put(hash, merchant);
            }
            return merchant;
        });
    }
}
