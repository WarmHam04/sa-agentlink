package io.agentlink.application;

import java.time.Instant;

public record OutboxMessage(
        long id,
        String tenantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        Instant createdAt,
        int attemptCount
) {
    public OutboxMessage(
            long id, String aggregateType, String aggregateId,
            String eventType, String payloadJson, Instant createdAt
    ) {
        this(id, "_default", aggregateType, aggregateId, eventType, payloadJson, createdAt, 0);
    }
}
