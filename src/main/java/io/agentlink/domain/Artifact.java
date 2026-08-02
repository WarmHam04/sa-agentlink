package io.agentlink.domain;

import java.time.Instant;
import java.util.Map;

public record Artifact(
        String id,
        String taskId,
        String name,
        String mimeType,
        String content,
        Map<String, String> metadata,
        Instant createdAt
) {
    public Artifact {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
