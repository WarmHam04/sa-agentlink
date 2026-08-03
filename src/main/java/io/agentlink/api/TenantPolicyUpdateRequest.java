package io.agentlink.api;

import jakarta.validation.constraints.Min;

public record TenantPolicyUpdateRequest(
        boolean enabled,
        @Min(1) int requestsPerMinute,
        @Min(1) int workflowsPerDay,
        @Min(1) int maxActiveWorkflows
) {
}
