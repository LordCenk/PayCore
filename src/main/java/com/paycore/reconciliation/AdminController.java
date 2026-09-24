package com.paycore.reconciliation;

import com.paycore.common.ApiException;
import com.paycore.common.Hashing;
import com.paycore.config.PayCoreProperties;
import com.paycore.payment.PaymentRetryJob;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints, protected by {@code X-Admin-Key}. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final ReconciliationService reconciliation;
    private final PaymentRetryJob retryJob;
    private final String adminKey;

    public AdminController(ReconciliationService reconciliation, PaymentRetryJob retryJob,
                           PayCoreProperties properties) {
        this.reconciliation = reconciliation;
        this.retryJob = retryJob;
        this.adminKey = properties.adminApiKey();
    }

    @PostMapping("/reconciliation/run")
    public ReconciliationReport runReconciliation(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String key) {
        requireAdmin(key);
        return reconciliation.run();
    }

    @PostMapping("/retries/run")
    public Map<String, Integer> runRetries(@RequestHeader(value = ADMIN_KEY_HEADER, required = false) String key) {
        requireAdmin(key);
        return Map.of("examined", retryJob.runOnce());
    }

    private void requireAdmin(String key) {
        if (!Hashing.constantTimeEquals(adminKey, key)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid admin key");
        }
    }
}
