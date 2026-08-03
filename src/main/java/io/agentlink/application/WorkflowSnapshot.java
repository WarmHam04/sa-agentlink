package io.agentlink.application;

import io.agentlink.domain.AgentTask;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.TaskState;
import io.agentlink.domain.Workflow;

import java.time.Instant;
import java.util.List;

public record WorkflowSnapshot(
        String contextId,
        String goal,
        Instant createdAt,
        List<TaskSnapshot> tasks
) {
    public static WorkflowSnapshot from(Workflow workflow) {
        return new WorkflowSnapshot(
                workflow.contextId(), workflow.goal(), workflow.createdAt(),
                workflow.tasks().stream().map(TaskSnapshot::from).toList()
        );
    }

    public Workflow restore(long version) {
        return Workflow.restore(
                contextId,
                goal,
                createdAt,
                tasks.stream().map(task -> task.restore(contextId)).toList(),
                version
        );
    }

    public record TaskSnapshot(
            String id,
            TaskNodeSpec spec,
            TaskState state,
            String agentId,
            Artifact artifact,
            String error,
            Instant createdAt,
            Instant updatedAt
    ) {
        static TaskSnapshot from(AgentTask task) {
            return new TaskSnapshot(
                    task.id(), task.spec(), task.state(), task.agentId(), task.artifact(), task.error(),
                    task.createdAt(), task.updatedAt()
            );
        }

        AgentTask restore(String contextId) {
            return AgentTask.restore(
                    id, contextId, spec, state, agentId, artifact, error, createdAt, updatedAt
            );
        }
    }
}
