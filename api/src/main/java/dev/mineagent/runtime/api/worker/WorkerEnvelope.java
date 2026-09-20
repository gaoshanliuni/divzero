package dev.mineagent.runtime.api.worker;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record WorkerEnvelope(
        int protocolVersion,
        UUID requestId,
        String type,
        Map<String, Object> payload
) {
    public WorkerEnvelope {
        if (protocolVersion < 1) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
