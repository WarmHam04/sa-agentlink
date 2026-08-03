package io.agentlink.api;

public record TenantIdentity(String tenantId, String subject, boolean systemAdmin) {
}
