package io.agentlink.infrastructure;

public class AgentProcessException extends RuntimeException {
    private final String runtime;

    public AgentProcessException(String runtime, String message, Throwable cause) {
        super(message, cause);
        this.runtime = runtime;
    }

    public String runtime() {
        return runtime;
    }
}
