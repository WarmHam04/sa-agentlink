package io.agentlink.infrastructure;

import io.agentlink.application.WorkflowLease;
import io.agentlink.application.WorkflowLeaseManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryWorkflowLeaseManager implements WorkflowLeaseManager {
    private final ConcurrentHashMap<String, WorkflowLease> leases = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkflowLease> acquire(String contextId, String ownerId, Duration ttl) {
        Instant now = Instant.now();
        WorkflowLease lease = leases.compute(contextId, (ignored, current) -> {
            if (current != null && current.leaseUntil().isAfter(now) && !current.ownerId().equals(ownerId)) {
                return current;
            }
            long token = current == null ? 1L : current.fencingToken() + 1L;
            return new WorkflowLease(contextId, ownerId, token, now.plus(ttl));
        });
        return lease.ownerId().equals(ownerId) ? Optional.of(lease) : Optional.empty();
    }

    @Override
    public Optional<WorkflowLease> renew(WorkflowLease lease, Duration ttl) {
        Instant now = Instant.now();
        WorkflowLease renewed = leases.computeIfPresent(lease.contextId(), (ignored, current) ->
                current.ownerId().equals(lease.ownerId()) && current.fencingToken() == lease.fencingToken()
                        && current.leaseUntil().isAfter(now)
                        ? new WorkflowLease(current.contextId(), current.ownerId(), current.fencingToken(), now.plus(ttl))
                        : current
        );
        return renewed != null && renewed.ownerId().equals(lease.ownerId())
                && renewed.fencingToken() == lease.fencingToken() && renewed.leaseUntil().isAfter(now)
                ? Optional.of(renewed) : Optional.empty();
    }

    @Override
    public void release(WorkflowLease lease) {
        leases.computeIfPresent(lease.contextId(), (ignored, current) ->
                current.ownerId().equals(lease.ownerId()) && current.fencingToken() == lease.fencingToken()
                        ? null : current
        );
    }
}
