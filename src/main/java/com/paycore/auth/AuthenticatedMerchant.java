package com.paycore.auth;

import com.paycore.merchant.Merchant;
import java.time.Instant;

/**
 * The merchant making the current request. An immutable snapshot rather than the JPA entity, so it can be
 * cached in Redis and passed around without dragging a persistence context along.
 */
public record AuthenticatedMerchant(String id, String name, String email, String status, String webhookUrl,
                                    Instant createdAt) {

    public static AuthenticatedMerchant of(Merchant m) {
        return new AuthenticatedMerchant(m.getId(), m.getName(), m.getEmail(), m.getStatus().name(),
                m.getWebhookUrl(), m.getCreatedAt());
    }

    public boolean isActive() {
        return Merchant.Status.ACTIVE.name().equals(status);
    }
}
