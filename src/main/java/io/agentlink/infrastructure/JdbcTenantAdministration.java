package io.agentlink.infrastructure;

import io.agentlink.application.TenantAdministration;
import io.agentlink.application.TenantPolicy;
import io.agentlink.application.TenantRoutingConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcTenantAdministration implements TenantAdministration {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcTenantControlPlane controlPlane;

    public JdbcTenantAdministration(JdbcTemplate jdbcTemplate, JdbcTenantControlPlane controlPlane) {
        this.jdbcTemplate = jdbcTemplate;
        this.controlPlane = controlPlane;
    }

    @Override
    public TenantPolicy policy(String tenantId) {
        try {
            return read(tenantId, false);
        } catch (IllegalArgumentException missing) {
            controlPlane.policyForWrite(tenantId);
            return read(tenantId, false);
        }
    }

    @Override
    @Transactional
    public TenantPolicy updateLimits(
            String tenantId,
            boolean enabled,
            int requestsPerMinute,
            int workflowsPerDay,
            int maxActiveWorkflows
    ) {
        validateLimits(requestsPerMinute, workflowsPerDay, maxActiveWorkflows);
        policy(tenantId);
        jdbcTemplate.update(
                """
                UPDATE agentlink_tenant_policy
                SET enabled = ?, requests_per_minute = ?, workflows_per_day = ?,
                    max_active_workflows = ?, updated_at = ?
                WHERE tenant_id = ?
                """,
                enabled, requestsPerMinute, workflowsPerDay, maxActiveWorkflows,
                Timestamp.from(Instant.now()), tenantId
        );
        return read(tenantId, false);
    }

    @Override
    @Transactional
    public TenantPolicy failover(String tenantId, String newHomeRegion, long expectedRoutingEpoch) {
        if (newHomeRegion == null || !newHomeRegion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("invalid home region");
        }
        TenantPolicy current = read(tenantId, true);
        if (current.routingEpoch() != expectedRoutingEpoch) {
            throw new TenantRoutingConflictException(tenantId, expectedRoutingEpoch);
        }
        long nextEpoch = current.routingEpoch() + 1;
        jdbcTemplate.update(
                """
                UPDATE agentlink_tenant_policy
                SET home_region = ?, routing_epoch = ?, updated_at = ? WHERE tenant_id = ?
                """,
                newHomeRegion, nextEpoch, Timestamp.from(Instant.now()), tenantId
        );
        jdbcTemplate.update(
                "UPDATE agentlink_workflow SET routing_epoch = ? WHERE tenant_id = ?",
                nextEpoch, tenantId
        );
        jdbcTemplate.update(
                """
                DELETE FROM agentlink_workflow_lease
                WHERE context_id IN (SELECT context_id FROM agentlink_workflow WHERE tenant_id = ?)
                """,
                tenantId
        );
        return read(tenantId, false);
    }

    private TenantPolicy read(String tenantId, boolean forUpdate) {
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
        ), tenantId).stream().findFirst().orElseThrow(
                () -> new IllegalArgumentException("tenant policy not found: " + tenantId)
        );
    }

    private void validateLimits(int requestsPerMinute, int workflowsPerDay, int maxActiveWorkflows) {
        if (requestsPerMinute < 1 || workflowsPerDay < 1 || maxActiveWorkflows < 1) {
            throw new IllegalArgumentException("tenant limits must be positive");
        }
    }
}
