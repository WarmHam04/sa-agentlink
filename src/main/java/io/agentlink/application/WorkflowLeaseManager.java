package io.agentlink.application;

import java.time.Duration;
import java.util.Optional;

public interface WorkflowLeaseManager {
    Optional<WorkflowLease> acquire(String contextId, String ownerId, Duration ttl);
    Optional<WorkflowLease> renew(WorkflowLease lease, Duration ttl);
    void release(WorkflowLease lease);
}
