package io.agentlink.domain;

public enum TaskState {
    SUBMITTED,
    WORKING,
    INPUT_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELED,
    REJECTED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }
}
