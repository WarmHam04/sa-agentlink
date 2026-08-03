package io.agentlink.application;

import java.util.List;

public interface OutboxDeadLetterService {
    List<OutboxDeadLetter> list(String tenantId, int limit);
    boolean replay(String tenantId, long outboxId);
}
