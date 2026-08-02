package io.agentlink.application;

import io.agentlink.domain.Workflow;

import java.util.Optional;

public interface WorkflowStore {
    Workflow saveIfAbsent(Workflow workflow);
    Optional<Workflow> find(String contextId);
}
