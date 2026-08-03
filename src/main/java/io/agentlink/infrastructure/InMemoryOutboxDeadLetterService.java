package io.agentlink.infrastructure;

import io.agentlink.application.OutboxDeadLetter;
import io.agentlink.application.OutboxDeadLetterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryOutboxDeadLetterService implements OutboxDeadLetterService {
    @Override
    public List<OutboxDeadLetter> list(String tenantId, int limit) {
        return List.of();
    }

    @Override
    public boolean replay(String tenantId, long outboxId) {
        return false;
    }
}
