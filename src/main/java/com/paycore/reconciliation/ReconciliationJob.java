package com.paycore.reconciliation;

import com.paycore.redis.DistributedLock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    static final String LOCK = "reconciliation";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final ReconciliationService service;
    private final DistributedLock lock;

    public ReconciliationJob(ReconciliationService service, DistributedLock lock) {
        this.service = service;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${paycore.jobs.reconciliation-interval}")
    /** One instance per run: reconciliation scans everything, so running it everywhere only duplicates work. */
    public void run() {
        lock.runExclusive(LOCK, LOCK_TTL, service::run);
    }
}
