package io.agentlink.application;

import io.agentlink.domain.TaskNodeSpec;

import java.util.List;

public record CreateWorkflowCommand(String contextId, String goal, List<TaskNodeSpec> nodes) {
}
