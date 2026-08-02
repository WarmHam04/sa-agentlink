package io.agentlink.domain;

import java.time.Instant;
import java.util.Map;

public record RuntimeEvent(
        String runtime,
        String type,
        String message,
        Map<String, Object> attributes,
        Instant timestamp
) {
    public RuntimeEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static RuntimeEvent of(String runtime, String type, String message) {
        return new RuntimeEvent(runtime, type, message, Map.of(), Instant.now());
    }
}
