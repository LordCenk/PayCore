package com.paycore.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private final ReconciliationService service;

    public ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${paycore.jobs.reconciliation-interval}")
    public void run() {
        service.run();
    }
}
