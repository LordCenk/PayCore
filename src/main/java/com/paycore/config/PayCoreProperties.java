package com.paycore.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paycore")
public record PayCoreProperties(
        Set<String> supportedCurrencies,
        Idempotency idempotency,
        Processor processor,
        Retry retry,
        Fraud fraud,
        Jobs jobs,
        Webhooks webhooks,
        Events events,
        Redis redis,
        RateLimit rateLimit,
        String adminApiKey) {

    public record Idempotency(Duration ttl, Duration inProgressTimeout) {}

    public record Processor(double successRate, double declineRate, String webhookSecret) {}

    public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {}

    public record Fraud(long maxAmount, int velocityLimit, Duration velocityWindow, Set<String> blockedTokens) {}

    public record Jobs(
            boolean enabled,
            Duration retryInterval,
            Duration reconciliationInterval,
            Duration reconciliationStaleAfter,
            Duration outboxInterval,
            Duration webhookDeliveryInterval) {}

    /** @param allowPrivateTargets allow webhook URLs on loopback/private networks (local development only) */
    public record Webhooks(int maxAttempts, Duration timeout, boolean allowPrivateTargets) {}

    /**
     * @param publisher {@code kafka} or {@code logging} (no broker needed)
     * @param topic     all PayCore domain events; failed consumer records go to {@code <topic>.DLT}
     */
    public record Events(String publisher, String topic, int partitions, Duration publishTimeout) {}

    public record Redis(Duration failureCooldown, Duration merchantCacheTtl) {}

    public record RateLimit(boolean enabled, int capacity, double refillPerSecond) {}
}
