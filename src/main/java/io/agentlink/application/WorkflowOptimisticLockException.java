package io.agentlink.application;

public class WorkflowOptimisticLockException extends RuntimeException {
    public WorkflowOptimisticLockException(String contextId, long expectedVersion) {
        super("workflow was concurrently modified: " + contextId + " at version " + expectedVersion);
    }
}
