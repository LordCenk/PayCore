package com.paycore.idempotency;

import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Wraps a controller action so repeating it with the same Idempotency-Key returns the first result. */
@Component
public class IdempotentRequestHandler {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final IdempotencyService idempotency;
    private final JsonMapper json;

    public IdempotentRequestHandler(IdempotencyService idempotency, JsonMapper json) {
        this.idempotency = idempotency;
        this.json = json;
    }

    /**
     * @param resume  renders the current state of a resource created by an earlier attempt that never finished
     * @param action  runs the request; must attach its resource with {@link IdempotencyService#attachResource}
     */
    public ResponseEntity<?> execute(String merchantId, String key, String requestHash,
                                     Function<String, Object> resume, Supplier<ResponseEntity<?>> action) {
        IdempotencyService.Outcome outcome = idempotency.begin(merchantId, key, requestHash);
        return switch (outcome) {
            case IdempotencyService.Replay replay -> ResponseEntity.status(replay.status())
                    .header(REPLAYED_HEADER, "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(replay.body());
            case IdempotencyService.Resume r -> ResponseEntity.ok()
                    .header(REPLAYED_HEADER, "true")
                    .body(resume.apply(r.resourceId()));
            case IdempotencyService.Proceed p -> proceed(merchantId, key, requestHash, action);
        };
    }

    private ResponseEntity<?> proceed(String merchantId, String key, String requestHash,
                                     Supplier<ResponseEntity<?>> action) {
        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            // Nothing was created (the transaction rolled back): free the key so a corrected retry can use it.
            idempotency.release(merchantId, key);
            throw e;
        }
        idempotency.complete(merchantId, key, requestHash, response.getStatusCode().value(),
                json.writeValueAsString(response.getBody()));
        return response;
    }
}
