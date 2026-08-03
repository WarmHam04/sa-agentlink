package io.agentlink.application;

import java.time.Duration;

public interface TenantControlPlane {
    TenantPolicy policyForWrite(String tenantId);
    long admitWorkflow(String tenantId, String contextId);
    void admitRequest(String tenantId, String operation);
    void renewWorkflow(String tenantId, String contextId, Duration ttl);
    void releaseWorkflow(String tenantId, String contextId);
}
