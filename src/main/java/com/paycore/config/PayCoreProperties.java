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

    public record Webhooks(int maxAttempts, Duration timeout) {}
}
