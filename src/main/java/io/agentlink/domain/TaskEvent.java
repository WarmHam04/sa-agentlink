package io.agentlink.domain;

import java.time.Instant;

public record TaskEvent(
        long offset,
        String tenantId,
        String contextId,
        String taskId,
        String nodeId,
        TaskState state,
        String message,
        String artifactId,
        Instant timestamp
) {
    public TaskEvent(
            long offset, String contextId, String taskId, String nodeId, TaskState state,
            String message, String artifactId, Instant timestamp
    ) {
        this(offset, "_default", contextId, taskId, nodeId, state, message, artifactId, timestamp);
    }

    public TaskEvent withOffset(long newOffset) {
        return new TaskEvent(newOffset, tenantId, contextId, taskId, nodeId, state, message, artifactId, timestamp);
    }
}
