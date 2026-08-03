package io.agentlink.application;

public record TenantPolicy(
        String tenantId,
        boolean enabled,
        String homeRegion,
        long routingEpoch,
        int requestsPerMinute,
        int workflowsPerDay,
        int maxActiveWorkflows
) {
}
