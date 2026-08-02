package io.agentlink.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentTask {
    private final String id = UUID.randomUUID().toString();
    private final String contextId;
    private final TaskNodeSpec spec;
    private final Instant createdAt = Instant.now();
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.SUBMITTED);
    private volatile String agentId;
    private volatile Artifact artifact;
    private volatile String error;
    private volatile Instant updatedAt = createdAt;

    public AgentTask(String contextId, TaskNodeSpec spec) {
        this.contextId = contextId;
        this.spec = spec;
    }

    public synchronized void assign(String selectedAgentId) {
        if (state.get().terminal()) {
            throw new IllegalStateException("terminal task cannot be assigned");
        }
        this.agentId = selectedAgentId;
        this.updatedAt = Instant.now();
    }

    public synchronized void transition(TaskState target, String failure) {
        TaskState current = state.get();
        if (!allowed(current, target)) {
            throw new IllegalStateException("invalid task transition: " + current + " -> " + target);
        }
        state.set(target);
        error = failure;
        updatedAt = Instant.now();
    }

    public synchronized void complete(Artifact result) {
        this.artifact = result;
        transition(TaskState.COMPLETED, null);
    }

    private boolean allowed(TaskState current, TaskState target) {
        if (current.terminal()) {
            return false;
        }
        return switch (current) {
            case SUBMITTED -> Set.of(TaskState.WORKING, TaskState.FAILED, TaskState.CANCELED, TaskState.REJECTED).contains(target);
            case WORKING -> Set.of(TaskState.INPUT_REQUIRED, TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELED).contains(target);
            case INPUT_REQUIRED -> Set.of(TaskState.WORKING, TaskState.FAILED, TaskState.CANCELED).contains(target);
            default -> false;
        };
    }

    public String id() { return id; }
    public String contextId() { return contextId; }
    public TaskNodeSpec spec() { return spec; }
    public TaskState state() { return state.get(); }
    public String agentId() { return agentId; }
    public Artifact artifact() { return artifact; }
    public String error() { return error; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
