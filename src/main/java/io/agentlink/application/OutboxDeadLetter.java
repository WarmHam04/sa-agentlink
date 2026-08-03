package io.agentlink.application;

import java.time.Instant;

public record OutboxDeadLetter(
        long id,
        String tenantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        int attemptCount,
        String reason,
        Instant createdAt,
        Instant deadLetteredAt
) {
}
