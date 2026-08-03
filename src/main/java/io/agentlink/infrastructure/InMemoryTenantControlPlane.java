package io.agentlink.infrastructure;

import io.agentlink.application.TenantControlPlane;
import io.agentlink.application.TenantPolicy;
import io.agentlink.application.TenantQuotaExceededException;
import io.agentlink.application.TenantAdministration;
import io.agentlink.application.TenantRegionMismatchException;
import io.agentlink.application.TenantRoutingConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryTenantControlPlane implements TenantControlPlane, TenantAdministration {
    private final String region;
    private final int requestsPerMinute;
    private final int workflowsPerDay;
    private final int maxActiveWorkflows;
    private final Duration reservationTtl;
    private final Map<String, Integer> buckets = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, TenantPolicy> policies = new HashMap<>();

    public InMemoryTenantControlPlane(
            @Value("${agentlink.region:${AGENTLINK_REGION:local}}") String region,
            @Value("${agentlink.tenancy.default-requests-per-minute:600}") int requestsPerMinute,
            @Value("${agentlink.tenancy.default-workflows-per-day:10000}") int workflowsPerDay,
            @Value("${agentlink.tenancy.default-max-active-workflows:100}") int maxActiveWorkflows,
            @Value("${agentlink.tenancy.reservation-ttl:PT15M}") Duration reservationTtl
    ) {
        this.region = region;
        this.requestsPerMinute = requestsPerMinute;
        this.workflowsPerDay = workflowsPerDay;
        this.maxActiveWorkflows = maxActiveWorkflows;
        this.reservationTtl = reservationTtl;
    }

    @Override
    public synchronized TenantPolicy policyForWrite(String tenantId) {
        TenantPolicy policy = resolvePolicy(tenantId);
        if (!policy.enabled()) {
            throw new TenantQuotaExceededException(tenantId, "disabled");
        }
        if (!policy.homeRegion().equals(region)) {
            throw new TenantRegionMismatchException(tenantId, policy.homeRegion(), region);
        }
        return policy;
    }

    @Override
    public synchronized long admitWorkflow(String tenantId, String contextId) {
        TenantPolicy tenantPolicy = policyForWrite(tenantId);
        Instant now = Instant.now();
        reservations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Reservation existing = reservations.get(contextId);
        if (existing != null) {
            if (!existing.tenantId().equals(tenantId)) {
                throw new TenantQuotaExceededException(tenantId, "context ownership");
            }
            return tenantPolicy.routingEpoch();
        }
        consume(tenantId, "workflow-day", now.truncatedTo(ChronoUnit.DAYS), tenantPolicy.workflowsPerDay());
        long active = reservations.values().stream().filter(value -> value.tenantId().equals(tenantId)).count();
        if (active >= tenantPolicy.maxActiveWorkflows()) {
            throw new TenantQuotaExceededException(tenantId, "maxActiveWorkflows");
        }
        reservations.put(contextId, new Reservation(tenantId, now.plus(reservationTtl)));
        return tenantPolicy.routingEpoch();
    }

    @Override
    public synchronized void admitRequest(String tenantId, String operation) {
        TenantPolicy tenantPolicy = policyForAdministration(tenantId);
        if (!tenantPolicy.enabled()) {
            throw new TenantQuotaExceededException(tenantId, "disabled");
        }
        consume(tenantId, operation, Instant.now().truncatedTo(ChronoUnit.MINUTES),
                tenantPolicy.requestsPerMinute());
    }

    @Override
    public synchronized void renewWorkflow(String tenantId, String contextId, Duration ttl) {
        reservations.computeIfPresent(contextId, (ignored, current) -> current.tenantId().equals(tenantId)
                ? new Reservation(tenantId, Instant.now().plus(ttl)) : current);
    }

    @Override
    public synchronized void releaseWorkflow(String tenantId, String contextId) {
        reservations.computeIfPresent(contextId,
                (ignored, current) -> current.tenantId().equals(tenantId) ? null : current);
    }

    private TenantPolicy resolvePolicy(String tenantId) {
        return policies.computeIfAbsent(tenantId, ignored -> new TenantPolicy(
                tenantId, true, region, 1L, requestsPerMinute, workflowsPerDay, maxActiveWorkflows
        ));
    }

    @Override
    public synchronized TenantPolicy policy(String tenantId) {
        return policyForAdministration(tenantId);
    }

    @Override
    public synchronized TenantPolicy updateLimits(
            String tenantId, boolean enabled, int requests, int workflows, int maxActive
    ) {
        if (requests < 1 || workflows < 1 || maxActive < 1) {
            throw new IllegalArgumentException("tenant limits must be positive");
        }
        TenantPolicy current = policyForAdministration(tenantId);
        TenantPolicy updated = new TenantPolicy(
                tenantId, enabled, current.homeRegion(), current.routingEpoch(), requests, workflows, maxActive
        );
        policies.put(tenantId, updated);
        return updated;
    }

    @Override
    public synchronized TenantPolicy failover(String tenantId, String newHomeRegion, long expectedEpoch) {
        TenantPolicy current = policyForAdministration(tenantId);
        if (current.routingEpoch() != expectedEpoch) {
            throw new TenantRoutingConflictException(tenantId, expectedEpoch);
        }
        TenantPolicy updated = new TenantPolicy(
                tenantId, current.enabled(), newHomeRegion, expectedEpoch + 1,
                current.requestsPerMinute(), current.workflowsPerDay(), current.maxActiveWorkflows()
        );
        policies.put(tenantId, updated);
        return updated;
    }

    private TenantPolicy policyForAdministration(String tenantId) {
        return policies.computeIfAbsent(tenantId, ignored -> new TenantPolicy(
                tenantId, true, region, 1L, requestsPerMinute, workflowsPerDay, maxActiveWorkflows
        ));
    }

    private void consume(String tenantId, String operation, Instant bucket, int limit) {
        String key = tenantId + '|' + operation + '|' + bucket;
        int next = buckets.getOrDefault(key, 0) + 1;
        if (next > limit) {
            throw new TenantQuotaExceededException(tenantId, operation);
        }
        buckets.put(key, next);
    }

    private record Reservation(String tenantId, Instant expiresAt) {
    }
}
