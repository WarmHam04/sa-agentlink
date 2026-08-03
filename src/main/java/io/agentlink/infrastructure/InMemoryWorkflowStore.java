package io.agentlink.infrastructure;

import io.agentlink.application.WorkflowStore;
import io.agentlink.domain.Workflow;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryWorkflowStore implements WorkflowStore {
    private final ConcurrentHashMap<String, Workflow> workflows = new ConcurrentHashMap<>();

    @Override
    public Workflow saveIfAbsent(Workflow workflow) {
        Workflow existing = workflows.putIfAbsent(workflow.contextId(), workflow);
        return existing == null ? workflow : existing;
    }

    @Override
    public synchronized void save(Workflow workflow) {
        workflows.put(workflow.contextId(), workflow);
        long version = workflow.version();
        workflow.markPersisted(version, version + 1);
    }

    @Override
    public Optional<Workflow> find(String contextId) {
        return Optional.ofNullable(workflows.get(contextId));
    }

    @Override
    public Optional<Workflow> findByTaskId(String taskId) {
        return workflows.values().stream()
                .filter(workflow -> workflow.tasks().stream().anyMatch(task -> task.id().equals(taskId)))
                .findFirst();
    }

    @Override
    public List<Workflow> findNonTerminal() {
        return workflows.values().stream()
                .filter(workflow -> workflow.tasks().stream().anyMatch(task -> !task.state().terminal()))
                .toList();
    }
}
