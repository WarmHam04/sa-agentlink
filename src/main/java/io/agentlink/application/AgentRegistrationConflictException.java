package io.agentlink.application;

public class AgentRegistrationConflictException extends RuntimeException {
    public AgentRegistrationConflictException(String agentId) {
        super("agent registration is owned by another live instance: " + agentId);
    }
}
