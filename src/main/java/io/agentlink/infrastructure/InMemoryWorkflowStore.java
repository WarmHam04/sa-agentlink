package io.agentlink.infrastructure;

import io.agentlink.application.WorkflowStore;
import io.agentlink.domain.Workflow;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryWorkflowStore implements WorkflowStore {
    private final ConcurrentHashMap<String, Workflow> workflows = new ConcurrentHashMap<>();

    @Override
    public Workflow saveIfAbsent(Workflow workflow) {
        Workflow existing = workflows.putIfAbsent(workflow.contextId(), workflow);
        return existing == null ? workflow : existing;
    }

    @Override
    public Optional<Workflow> find(String contextId) {
        return Optional.ofNullable(workflows.get(contextId));
    }
}
