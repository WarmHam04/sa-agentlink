package io.agentlink.application;

public class WorkflowLeaseUnavailableException extends RuntimeException {
    public WorkflowLeaseUnavailableException(String contextId) {
        super("workflow is owned by another coordinator instance: " + contextId);
    }
}
