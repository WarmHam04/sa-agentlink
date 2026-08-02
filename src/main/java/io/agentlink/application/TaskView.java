package io.agentlink.application;

import io.agentlink.domain.AgentTask;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TaskView(
        String taskId,
        String nodeId,
        String skill,
        String instruction,
        List<String> dependsOn,
        Map<String, String> metadata,
        TaskState state,
        String agentId,
        Artifact artifact,
        String error,
        Instant updatedAt
) {
    public static TaskView from(AgentTask task) {
        return new TaskView(
                task.id(), task.spec().nodeId(), task.spec().skill(), task.spec().instruction(),
                task.spec().dependsOn(), task.spec().metadata(), task.state(), task.agentId(), task.artifact(), task.error(), task.updatedAt()
        );
    }
}
