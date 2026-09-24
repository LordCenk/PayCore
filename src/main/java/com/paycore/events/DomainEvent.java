package com.paycore.events;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An outbox event as consumers read it from Kafka: {@code {eventId, type, occurredAt, data}}. */
public record DomainEvent(String eventId, String type, Instant occurredAt, JsonNode data) {

    /** Throws {@link IllegalArgumentException} for malformed messages; they go to the dead-letter topic. */
    public static DomainEvent parse(JsonMapper json, String payload) {
        JsonNode root = json.readTree(payload);
        String eventId = text(root, "eventId");
        String type = text(root, "type");
        Instant occurredAt = Instant.parse(text(root, "occurredAt"));
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("Event has no data object");
        }
        return new DomainEvent(eventId, type, occurredAt, data);
    }

    public String dataText(String field) {
        return text(data, field);
    }

    public long dataLong(String field) {
        JsonNode node = data.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new IllegalArgumentException("Event data field '" + field + "' is missing or not an integer");
        }
        return node.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Event field '" + field + "' is missing");
        }
        return value.asString();
    }
}
