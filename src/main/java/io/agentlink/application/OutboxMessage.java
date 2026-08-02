package io.agentlink.application;

import java.time.Instant;

public record OutboxMessage(
        long id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        Instant createdAt
) {
}
