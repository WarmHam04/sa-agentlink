package io.agentlink.application;

public class TenantRoutingConflictException extends RuntimeException {
    public TenantRoutingConflictException(String tenantId, long expectedEpoch) {
        super("tenant routing epoch conflict: " + tenantId + " expected " + expectedEpoch);
    }
}
