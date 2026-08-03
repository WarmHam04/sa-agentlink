package io.agentlink.application;

public interface TenantAdministration {
    TenantPolicy policy(String tenantId);
    TenantPolicy updateLimits(
            String tenantId,
            boolean enabled,
            int requestsPerMinute,
            int workflowsPerDay,
            int maxActiveWorkflows
    );
    TenantPolicy failover(String tenantId, String newHomeRegion, long expectedRoutingEpoch);
}
