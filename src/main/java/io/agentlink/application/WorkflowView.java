package io.agentlink.application;

import io.agentlink.domain.Workflow;

import java.time.Instant;
import java.util.List;

public record WorkflowView(String contextId, String goal, Instant createdAt, List<TaskView> tasks) {
    public static WorkflowView from(Workflow workflow) {
        return new WorkflowView(
                workflow.contextId(), workflow.goal(), workflow.createdAt(),
                workflow.tasks().stream().map(TaskView::from).toList()
        );
    }
}
