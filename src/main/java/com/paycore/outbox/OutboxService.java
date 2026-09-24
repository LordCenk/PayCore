package com.paycore.outbox;

import com.paycore.common.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OutboxService {

    private final OutboxEventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxService(OutboxEventRepository events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** Must run inside the transaction that makes the state change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, String aggregateId, String partitionKey, String merchantId,
                        String eventType, Map<String, Object> data) {
        String eventId = Ids.newId("evt");
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("type", eventType);
        payload.put("occurredAt", now.toString());
        payload.put("data", data);
        events.save(new OutboxEvent(eventId, aggregateType, aggregateId, partitionKey, merchantId, eventType,
                json.writeValueAsString(payload), now));
    }
}
