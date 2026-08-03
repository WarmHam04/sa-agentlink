package io.agentlink.application;

import io.agentlink.domain.TaskNodeSpec;

import java.util.List;

public record CreateWorkflowCommand(String tenantId, String contextId, String goal, List<TaskNodeSpec> nodes) {
    public CreateWorkflowCommand(String contextId, String goal, List<TaskNodeSpec> nodes) {
        this("_default", contextId, goal, nodes);
    }
}
