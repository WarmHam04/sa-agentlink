package io.agentlink.application;

public class WorkflowLeaseLostException extends RuntimeException {
    public WorkflowLeaseLostException(String contextId, long fencingToken) {
        super("workflow execution lease was lost: " + contextId + " fencingToken=" + fencingToken);
    }
}
