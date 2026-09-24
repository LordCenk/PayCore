package com.paycore.webhook.inbound;

import com.paycore.common.ApiException;
import com.paycore.common.Hashing;
import com.paycore.config.PayCoreProperties;
import com.paycore.processor.PaymentProcessor;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class WebhookController {

    public static final String SIGNATURE_HEADER = "X-Processor-Signature";

    private final InboundWebhookService service;
    private final PaymentProcessor processor;
    private final JsonMapper json;
    private final String secret;

    public WebhookController(InboundWebhookService service, PaymentProcessor processor, JsonMapper json,
                             PayCoreProperties properties) {
        this.service = service;
        this.processor = processor;
        this.json = json;
        this.secret = properties.processor().webhookSecret();
    }

    /**
     * Verifies the HMAC-SHA256 signature of the raw body, stores the event, then processes it.
     * Returns 200 for duplicates too, so the processor stops retrying.
     */
    @PostMapping("/api/v1/webhooks/payment")
    public Map<String, Object> receive(@RequestBody String rawBody,
                                       @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (!Hashing.constantTimeEquals(Hashing.hmacSha256Hex(secret, rawBody), signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature is invalid");
        }
        ProcessorWebhook webhook;
        try {
            webhook = json.readValue(rawBody, ProcessorWebhook.class);
        } catch (JacksonException e) {
            throw ApiException.badRequest("INVALID_JSON", "Webhook body is not valid JSON");
        }
        if (webhook.id() == null || webhook.id().isBlank() || webhook.type() == null) {
            throw ApiException.badRequest("INVALID_WEBHOOK", "Webhook must have id and type");
        }
        InboundWebhookService.Receipt receipt = service.receive(processor.name(), webhook, rawBody);
        return Map.of("received", true, "duplicate", receipt == InboundWebhookService.Receipt.DUPLICATE);
    }
}
