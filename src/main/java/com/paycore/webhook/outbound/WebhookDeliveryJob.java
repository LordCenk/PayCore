package com.paycore.webhook.outbound;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookDeliveryJob {

    private final WebhookDeliveryService service;

    public WebhookDeliveryJob(WebhookDeliveryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${paycore.jobs.webhook-delivery-interval}")
    public void run() {
        service.deliverDue();
    }
}
