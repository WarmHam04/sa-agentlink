package io.agentlink.application;

import io.agentlink.domain.Workflow;

import java.util.Optional;
import java.util.List;

public interface WorkflowStore {
    Workflow saveIfAbsent(Workflow workflow);
    void save(Workflow workflow);
    default void save(Workflow workflow, WorkflowLease lease) {
        save(workflow);
    }
    Optional<Workflow> find(String contextId);
    Optional<Workflow> findByTaskId(String taskId);
    default Optional<Workflow> findForTenant(String tenantId, String contextId) {
        return find(contextId).filter(workflow -> workflow.tenantId().equals(tenantId));
    }
    default Optional<Workflow> findByTaskIdForTenant(String tenantId, String taskId) {
        return findByTaskId(taskId).filter(workflow -> workflow.tenantId().equals(tenantId));
    }
    List<Workflow> findNonTerminal();
}
