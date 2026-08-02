package io.agentlink.domain;

import java.time.Instant;

public record TaskEvent(
        long offset,
        String contextId,
        String taskId,
        String nodeId,
        TaskState state,
        String message,
        String artifactId,
        Instant timestamp
) {
    public TaskEvent withOffset(long newOffset) {
        return new TaskEvent(newOffset, contextId, taskId, nodeId, state, message, artifactId, timestamp);
    }
}
