package io.agentlink.infrastructure;

import io.agentlink.application.TenantControlPlane;
import io.agentlink.application.TenantPolicy;
import io.agentlink.application.TenantQuotaExceededException;
import io.agentlink.application.TenantRegionMismatchException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcTenantControlPlane implements TenantControlPlane {
    private final JdbcTemplate jdbcTemplate;
    private final String region;
    private final int defaultRequestsPerMinute;
    private final int defaultWorkflowsPerDay;
    private final int defaultMaxActiveWorkflows;
    private final Duration reservationTtl;
    private final boolean postgres;

    public JdbcTenantControlPlane(
            JdbcTemplate jdbcTemplate,
            @Value("${agentlink.region:${AGENTLINK_REGION:local}}") String region,
            @Value("${agentlink.tenancy.default-requests-per-minute:600}") int defaultRequestsPerMinute,
            @Value("${agentlink.tenancy.default-workflows-per-day:10000}") int defaultWorkflowsPerDay,
            @Value("${agentlink.tenancy.default-max-active-workflows:100}") int defaultMaxActiveWorkflows,
            @Value("${agentlink.tenancy.reservation-ttl:PT15M}") Duration reservationTtl
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.region = region;
        this.defaultRequestsPerMinute = defaultRequestsPerMinute;
        this.defaultWorkflowsPerDay = defaultWorkflowsPerDay;
        this.defaultMaxActiveWorkflows = defaultMaxActiveWorkflows;
        this.reservationTtl = reservationTtl;
        try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception failure) {
            throw new IllegalStateException("failed to identify tenant control database", failure);
        }
    }

    @Override
    @Transactional
    public TenantPolicy policyForWrite(String tenantId) {
        ensurePolicy(tenantId);
        TenantPolicy policy = readPolicy(tenantId, false).orElseThrow();
        requireWritable(policy);
        return policy;
    }

    @Override
    @Transactional
    public long admitWorkflow(String tenantId, String contextId) {
        ensurePolicy(tenantId);
        TenantPolicy policy = readPolicy(tenantId, true).orElseThrow();
        requireWritable(policy);
        Instant now = databaseNow();
        jdbcTemplate.update(
                "DELETE FROM agentlink_tenant_workflow_reservation WHERE tenant_id = ? AND expires_at <= ?",
                tenantId, Timestamp.from(now)
        );
        Optional<String> owner = jdbcTemplate.query(
                "SELECT tenant_id FROM agentlink_tenant_workflow_reservation WHERE context_id = ?",
                (resultSet, row) -> resultSet.getString(1), contextId
        ).stream().findFirst();
        if (owner.isPresent()) {
            if (!owner.get().equals(tenantId)) {
                throw new TenantQuotaExceededException(tenantId, "context ownership");
            }
            return policy.routingEpoch();
        }
        consumeBucket(tenantId, "workflow-day", now.truncatedTo(ChronoUnit.DAYS),
                policy.workflowsPerDay());
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agentlink_tenant_workflow_reservation WHERE tenant_id = ?",
                Integer.class, tenantId
        );
        if (active != null && active >= policy.maxActiveWorkflows()) {
            throw new TenantQuotaExceededException(tenantId, "maxActiveWorkflows");
        }
        jdbcTemplate.update(
                """
                INSERT INTO agentlink_tenant_workflow_reservation
                    (context_id, tenant_id, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                contextId, tenantId, Timestamp.from(now.plus(reservationTtl)),
                Timestamp.from(now), Timestamp.from(now)
        );
        return policy.routingEpoch();
    }

    @Override
    @Transactional
    public void admitRequest(String tenantId, String operation) {
        ensurePolicy(tenantId);
        TenantPolicy policy = readPolicy(tenantId, false).orElseThrow();
        if (!policy.enabled()) {
            throw new TenantQuotaExceededException(tenantId, "disabled");
        }
        Instant now = databaseNow();
        consumeBucket(tenantId, operation, now.truncatedTo(ChronoUnit.MINUTES),
                policy.requestsPerMinute());
    }

    @Override
    public void renewWorkflow(String tenantId, String contextId, Duration ttl) {
        Instant now = databaseNow();
        jdbcTemplate.update(
                """
                UPDATE agentlink_tenant_workflow_reservation SET expires_at = ?, updated_at = ?
                WHERE context_id = ? AND tenant_id = ?
                """,
                Timestamp.from(now.plus(ttl)), Timestamp.from(now), contextId, tenantId
        );
    }

    @Override
    public void releaseWorkflow(String tenantId, String contextId) {
        jdbcTemplate.update(
                "DELETE FROM agentlink_tenant_workflow_reservation WHERE context_id = ? AND tenant_id = ?",
                contextId, tenantId
        );
    }

    private void ensurePolicy(String tenantId) {
        Instant now = databaseNow();
        String sql = """
                INSERT INTO agentlink_tenant_policy
                    (tenant_id, enabled, home_region, routing_epoch, requests_per_minute,
                     workflows_per_day, max_active_workflows, updated_at)
                VALUES (?, TRUE, ?, 1, ?, ?, ?, ?)
                """;
        Object[] arguments = {
                tenantId, region, defaultRequestsPerMinute, defaultWorkflowsPerDay,
                defaultMaxActiveWorkflows, Timestamp.from(now)
        };
        if (postgres) {
            jdbcTemplate.update(sql + " ON CONFLICT (tenant_id) DO NOTHING", arguments);
            return;
        }
        try {
            jdbcTemplate.update(sql, arguments);
        } catch (DuplicateKeyException ignored) {
            // An existing policy is authoritative.
        }
    }

    private Optional<TenantPolicy> readPolicy(String tenantId, boolean forUpdate) {
        String sql = """
                SELECT tenant_id, enabled, home_region, routing_epoch, requests_per_minute,
                       workflows_per_day, max_active_workflows
                FROM agentlink_tenant_policy WHERE tenant_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbcTemplate.query(sql, (resultSet, row) -> new TenantPolicy(
                resultSet.getString("tenant_id"), resultSet.getBoolean("enabled"),
                resultSet.getString("home_region"), resultSet.getLong("routing_epoch"),
                resultSet.getInt("requests_per_minute"), resultSet.getInt("workflows_per_day"),
                resultSet.getInt("max_active_workflows")
        ), tenantId).stream().findFirst();
    }

    private void requireWritable(TenantPolicy policy) {
        if (!policy.enabled()) {
            throw new TenantQuotaExceededException(policy.tenantId(), "disabled");
        }
        if (!policy.homeRegion().equals(region)) {
            throw new TenantRegionMismatchException(policy.tenantId(), policy.homeRegion(), region);
        }
    }

    private void consumeBucket(String tenantId, String operation, Instant bucket, int limit) {
        Instant now = databaseNow();
        String sql = """
                INSERT INTO agentlink_tenant_rate_bucket
                    (tenant_id, operation, bucket_start, request_count, updated_at)
                VALUES (?, ?, ?, 1, ?)
                """;
        Object[] arguments = {tenantId, operation, Timestamp.from(bucket), Timestamp.from(now)};
        if (postgres && jdbcTemplate.update(
                sql + " ON CONFLICT (tenant_id, operation, bucket_start) DO NOTHING", arguments
        ) == 1) {
            return;
        }
        if (!postgres) {
            try {
                jdbcTemplate.update(sql, arguments);
                return;
            } catch (DuplicateKeyException ignored) {
                // Increment the existing fixed window atomically below.
            }
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE agentlink_tenant_rate_bucket
                SET request_count = request_count + 1, updated_at = ?
                WHERE tenant_id = ? AND operation = ? AND bucket_start = ? AND request_count < ?
                """,
                Timestamp.from(now), tenantId, operation, Timestamp.from(bucket), limit
        );
        if (updated != 1) {
            throw new TenantQuotaExceededException(tenantId, operation);
        }
    }

    private Instant databaseNow() {
        Timestamp value = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return value == null ? Instant.now() : value.toInstant();
    }
}
