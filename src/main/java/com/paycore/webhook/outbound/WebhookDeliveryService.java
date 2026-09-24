package com.paycore.webhook.outbound;

import com.paycore.common.Hashing;
import com.paycore.config.PayCoreProperties;
import com.paycore.merchant.Merchant;
import com.paycore.merchant.MerchantRepository;
import com.paycore.observability.PayCoreMetrics;
import com.paycore.outbox.OutboxEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers events to merchants' webhook URLs, at least once.
 *
 * <p>Each request is signed: {@code X-PayCore-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * Merchants verify it with their webhook secret and de-duplicate on {@code X-PayCore-Event-Id}.
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final int BATCH_SIZE = 20;

    public static final String EVENT_ID_HEADER = "X-PayCore-Event-Id";
    public static final String EVENT_TYPE_HEADER = "X-PayCore-Event-Type";
    public static final String SIGNATURE_HEADER = "X-PayCore-Signature";

    private final WebhookDeliveryRepository deliveries;
    private final MerchantRepository merchants;
    private final TransactionTemplate tx;
    private final PayCoreProperties.Webhooks config;
    private final HttpClient http;
    private final PayCoreMetrics metrics;
    private final WebhookTargetPolicy targetPolicy;
    private final Clock clock;

    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, MerchantRepository merchants,
                                  PlatformTransactionManager txManager, PayCoreProperties properties,
                                  PayCoreMetrics metrics, WebhookTargetPolicy targetPolicy, Clock clock) {
        this.deliveries = deliveries;
        this.merchants = merchants;
        this.tx = new TransactionTemplate(txManager);
        this.config = properties.webhooks();
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        this.metrics = metrics;
        this.targetPolicy = targetPolicy;
        this.clock = clock;
    }

    /** Called by the outbox relay, inside its transaction. No-op if the merchant has no webhook URL. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboxEvent event) {
        merchants.findById(event.getMerchantId())
                .filter(m -> m.getWebhookUrl() != null && !m.getWebhookUrl().isBlank())
                .ifPresent(m -> deliveries.tryInsert(m.getId(), event.getEventId(), event.getEventType(),
                        event.getPayload(), clock.instant()));
    }

    private record Claimed(long id, String url, String secret, String eventId, String eventType, String payload) {}

    /** Returns the number of deliveries attempted. */
    public int deliverDue() {
        List<Claimed> claimed = tx.execute(s -> claim());
        if (claimed == null) {
            return 0;
        }
        for (Claimed c : claimed) {
            send(c);
        }
        return claimed.size();
    }

    /** Leases due rows (pushes next_attempt_at forward) so other instances skip them while we send. */
    private List<Claimed> claim() {
        Instant now = clock.instant();
        List<Claimed> claimed = new ArrayList<>();
        for (WebhookDelivery d : deliveries.lockDue(now, BATCH_SIZE)) {
            Merchant merchant = merchants.findById(d.getMerchantId()).orElseThrow();
            d.lease(now.plus(config.timeout().multipliedBy(2)), now);
            claimed.add(new Claimed(d.getId(), merchant.getWebhookUrl(), merchant.getWebhookSecret(), d.getEventId(),
                    d.getEventType(), d.getPayload()));
        }
        return claimed;
    }

    private record Attempt(Integer code, String error) {
        boolean delivered() {
            return code != null && code >= 200 && code < 300;
        }
    }

    private void send(Claimed c) {
        Attempt attempt = targetPolicy.blockedReason(c.url())
                .map(reason -> new Attempt(null, "blocked: " + reason))
                .orElseGet(() -> post(c));
        boolean delivered = attempt.delivered();
        if (!delivered) {
            log.warn("webhook delivery failed event={} code={} error={}", c.eventId(), attempt.code(), attempt.error());
        }
        String error = attempt.error() == null && !delivered ? "HTTP " + attempt.code() : attempt.error();
        tx.executeWithoutResult(s -> deliveries.findById(c.id()).ifPresent(d -> {
            Instant now = clock.instant();
            d.recordAttempt(delivered, attempt.code(), error, config.maxAttempts(),
                    now.plus(backoff(d.getAttemptCount() + 1)), now);
            metrics.webhookDelivery(delivered ? "delivered"
                    : d.getStatus() == WebhookDelivery.Status.FAILED ? "gave_up" : "failed_attempt");
        }));
    }

    private Attempt post(Claimed c) {
        try {
            long timestamp = clock.instant().getEpochSecond();
            String signature = "t=" + timestamp + ",v1=" + Hashing.hmacSha256Hex(c.secret(), timestamp + "." + c.payload());
            HttpRequest request = HttpRequest.newBuilder(URI.create(c.url()))
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header(EVENT_ID_HEADER, c.eventId())
                    .header(EVENT_TYPE_HEADER, c.eventType())
                    .header(SIGNATURE_HEADER, signature)
                    .POST(HttpRequest.BodyPublishers.ofString(c.payload()))
                    .build();
            return new Attempt(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt(null, "interrupted");
        } catch (Exception e) {
            return new Attempt(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 5s, 10s, 20s ... capped at 1 hour. */
    static Duration backoff(int attempt) {
        long seconds = Math.min(5L << Math.min(attempt - 1, 20), 3600);
        return Duration.ofSeconds(seconds);
    }
}
