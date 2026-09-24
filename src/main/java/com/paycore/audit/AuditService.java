package com.paycore.audit;

import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AuditService {

    private final AuditLogRepository logs;
    private final JsonMapper json;
    private final Clock clock;

    public AuditService(AuditLogRepository logs, JsonMapper json, Clock clock) {
        this.logs = logs;
        this.json = json;
        this.clock = clock;
    }

    /** Joins the caller's transaction, so the audit row commits or rolls back with the change it describes. */
    @Transactional
    public void record(String entityType, String entityId, String action, String oldValue, String newValue,
                       String actor, Map<String, ?> metadata) {
        String meta = metadata == null || metadata.isEmpty() ? null : json.writeValueAsString(metadata);
        logs.save(new AuditLog(entityType, entityId, action, oldValue, newValue, actor, meta, clock.instant()));
    }
}
