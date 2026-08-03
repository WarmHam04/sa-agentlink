package io.agentlink.infrastructure;

import io.agentlink.application.WorkflowLease;
import io.agentlink.application.WorkflowLeaseManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcWorkflowLeaseManager implements WorkflowLeaseManager {
    private final JdbcTemplate jdbcTemplate;
    private final boolean postgres;
    private final String region;

    public JdbcWorkflowLeaseManager(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, "local");
    }

    @Autowired
    public JdbcWorkflowLeaseManager(
            JdbcTemplate jdbcTemplate,
            @Value("${agentlink.region:${AGENTLINK_REGION:local}}") String region
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.region = region;
        try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception failure) {
            throw new IllegalStateException("failed to identify lease database", failure);
        }
    }

    @Override
    @Transactional
    public Optional<WorkflowLease> acquire(String contextId, String ownerId, Duration ttl) {
        if (!writableInThisRegion(contextId)) {
            return Optional.empty();
        }
        Instant now = databaseNow();
        Instant until = now.plus(ttl);
        int inserted = insertIfAbsent(contextId, ownerId, until, now);
        if (inserted == 0) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE agentlink_workflow_lease
                    SET owner_id = ?, fencing_token = fencing_token + 1, lease_until = ?, updated_at = ?
                    WHERE context_id = ? AND (lease_until <= ? OR owner_id = ?)
                    """,
                    ownerId, Timestamp.from(until), Timestamp.from(now), contextId, Timestamp.from(now), ownerId
            );
            if (updated == 0) {
                return Optional.empty();
            }
        }
        return current(contextId).filter(lease -> lease.ownerId().equals(ownerId));
    }

    @Override
    @Transactional
    public Optional<WorkflowLease> renew(WorkflowLease lease, Duration ttl) {
        Instant now = databaseNow();
        Instant until = now.plus(ttl);
        int updated = jdbcTemplate.update(
                """
                UPDATE agentlink_workflow_lease
                SET lease_until = ?, updated_at = ?
                WHERE context_id = ? AND owner_id = ? AND fencing_token = ? AND lease_until > ?
                  AND EXISTS (
                      SELECT 1 FROM agentlink_workflow workflow
                      LEFT JOIN agentlink_tenant_policy policy ON policy.tenant_id = workflow.tenant_id
                      WHERE workflow.context_id = agentlink_workflow_lease.context_id
                        AND (policy.tenant_id IS NULL OR (
                            policy.enabled = TRUE AND policy.home_region = ?
                            AND policy.routing_epoch = workflow.routing_epoch
                        ))
                  )
                """,
                Timestamp.from(until), Timestamp.from(now), lease.contextId(), lease.ownerId(),
                lease.fencingToken(), Timestamp.from(now), region
        );
        return updated == 1 ? Optional.of(new WorkflowLease(
                lease.contextId(), lease.ownerId(), lease.fencingToken(), until
        )) : Optional.empty();
    }

    @Override
    public void release(WorkflowLease lease) {
        jdbcTemplate.update(
                "DELETE FROM agentlink_workflow_lease WHERE context_id = ? AND owner_id = ? AND fencing_token = ?",
                lease.contextId(), lease.ownerId(), lease.fencingToken()
        );
    }

    private int insertIfAbsent(String contextId, String ownerId, Instant until, Instant now) {
        String sql = """
                INSERT INTO agentlink_workflow_lease
                    (context_id, owner_id, fencing_token, lease_until, updated_at)
                VALUES (?, ?, 1, ?, ?)
                """;
        Object[] arguments = {contextId, ownerId, Timestamp.from(until), Timestamp.from(now)};
        if (postgres) {
            return jdbcTemplate.update(sql + " ON CONFLICT (context_id) DO NOTHING", arguments);
        }
        try {
            return jdbcTemplate.update(sql, arguments);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            return 0;
        }
    }

    private Optional<WorkflowLease> current(String contextId) {
        return jdbcTemplate.query(
                """
                SELECT context_id, owner_id, fencing_token, lease_until
                FROM agentlink_workflow_lease WHERE context_id = ?
                """,
                (resultSet, row) -> new WorkflowLease(
                        resultSet.getString("context_id"), resultSet.getString("owner_id"),
                        resultSet.getLong("fencing_token"), resultSet.getTimestamp("lease_until").toInstant()
                ),
                contextId
        ).stream().findFirst();
    }

    private boolean writableInThisRegion(String contextId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM agentlink_workflow workflow
                LEFT JOIN agentlink_tenant_policy policy ON policy.tenant_id = workflow.tenant_id
                WHERE workflow.context_id = ?
                  AND (policy.tenant_id IS NULL OR (
                      policy.enabled = TRUE AND policy.home_region = ?
                      AND policy.routing_epoch = workflow.routing_epoch
                  ))
                """,
                Integer.class, contextId, region
        );
        return count != null && count == 1;
    }

    private Instant databaseNow() {
        Timestamp value = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return value == null ? Instant.now() : value.toInstant();
    }
}
