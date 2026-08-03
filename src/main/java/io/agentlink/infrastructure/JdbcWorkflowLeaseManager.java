package io.agentlink.infrastructure;

import io.agentlink.application.WorkflowLease;
import io.agentlink.application.WorkflowLeaseManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    public JdbcWorkflowLeaseManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception failure) {
            throw new IllegalStateException("failed to identify lease database", failure);
        }
    }

    @Override
    @Transactional
    public Optional<WorkflowLease> acquire(String contextId, String ownerId, Duration ttl) {
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
                """,
                Timestamp.from(until), Timestamp.from(now), lease.contextId(), lease.ownerId(),
                lease.fencingToken(), Timestamp.from(now)
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

    private Instant databaseNow() {
        Timestamp value = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return value == null ? Instant.now() : value.toInstant();
    }
}
