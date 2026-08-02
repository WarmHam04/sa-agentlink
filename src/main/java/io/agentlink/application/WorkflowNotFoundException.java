package io.agentlink.application;

public class WorkflowNotFoundException extends RuntimeException {
    public WorkflowNotFoundException(String contextId) {
        super("workflow not found: " + contextId);
    }
}
