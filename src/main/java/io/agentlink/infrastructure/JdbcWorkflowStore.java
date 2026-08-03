package io.agentlink.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.WorkflowSnapshot;
import io.agentlink.application.WorkflowStore;
import io.agentlink.application.WorkflowOptimisticLockException;
import io.agentlink.application.WorkflowLease;
import io.agentlink.application.WorkflowLeaseLostException;
import io.agentlink.domain.AgentTask;
import io.agentlink.domain.Workflow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcWorkflowStore implements WorkflowStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean postgres;

    public JdbcWorkflowStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception failure) {
            throw new IllegalStateException("failed to identify workflow database", failure);
        }
    }

    @Override
    @Transactional
    public Workflow saveIfAbsent(Workflow workflow) {
        String snapshot = serialize(WorkflowSnapshot.from(workflow));
        int inserted = insertWorkflow(workflow, snapshot);
        if (inserted == 1) {
            syncTasks(workflow);
            writeOutbox(workflow.tenantId(), workflow.contextId(), "workflow.created", snapshot);
            return workflow;
        }
        return find(workflow.contextId())
                .orElseThrow(() -> new IllegalStateException("workflow conflict without persisted row"));
    }

    private int insertWorkflow(Workflow workflow, String snapshot) {
        String baseSql = """
                INSERT INTO agentlink_workflow
                    (context_id, tenant_id, routing_epoch, goal, definition_hash, snapshot_json,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """;
        Object[] arguments = {
                workflow.contextId(), workflow.tenantId(), workflow.routingEpoch(),
                workflow.goal(), definitionHash(workflow), snapshot,
                Timestamp.from(workflow.createdAt()), Timestamp.from(Instant.now())
        };
        if (postgres) {
            return jdbcTemplate.update(baseSql + " ON CONFLICT (context_id) DO NOTHING", arguments);
        }
        try {
            return jdbcTemplate.update(baseSql, arguments);
        } catch (DuplicateKeyException ignored) {
            return 0;
        }
    }

    @Override
    @Transactional
    public void save(Workflow workflow) {
        saveInternal(workflow, null);
    }

    @Override
    @Transactional
    public void save(Workflow workflow, WorkflowLease lease) {
        saveInternal(workflow, lease);
    }

    private void saveInternal(Workflow workflow, WorkflowLease lease) {
        String snapshot = serialize(WorkflowSnapshot.from(workflow));
        long expectedVersion = workflow.version();
        String sql = """
                UPDATE agentlink_workflow
                SET snapshot_json = ?, updated_at = ?, version = version + 1
                WHERE context_id = ? AND tenant_id = ? AND routing_epoch = ? AND version = ?
                """;
        Object[] arguments;
        if (lease == null) {
            arguments = new Object[]{
                    snapshot, Timestamp.from(Instant.now()), workflow.contextId(), workflow.tenantId(),
                    workflow.routingEpoch(), expectedVersion
            };
        } else {
            sql += """
                     AND EXISTS (
                         SELECT 1 FROM agentlink_workflow_lease lease
                         WHERE lease.context_id = agentlink_workflow.context_id
                           AND lease.owner_id = ? AND lease.fencing_token = ?
                           AND lease.lease_until > CURRENT_TIMESTAMP
                     )
                    """;
            arguments = new Object[]{
                    snapshot, Timestamp.from(Instant.now()), workflow.contextId(), workflow.tenantId(),
                    workflow.routingEpoch(), expectedVersion,
                    lease.ownerId(), lease.fencingToken()
            };
        }
        int updated = jdbcTemplate.update(sql, arguments);
        if (updated != 1) {
            if (lease != null && find(workflow.contextId())
                    .map(current -> current.version() == expectedVersion).orElse(false)) {
                throw new WorkflowLeaseLostException(workflow.contextId(), lease.fencingToken());
            }
            throw new WorkflowOptimisticLockException(workflow.contextId(), expectedVersion);
        }
        syncTasks(workflow);
        writeOutbox(workflow.tenantId(), workflow.contextId(), "workflow.snapshot.updated", snapshot);
        workflow.markPersisted(expectedVersion, expectedVersion + 1);
    }

    @Override
    public Optional<Workflow> find(String contextId) {
        return jdbcTemplate.query(
                "SELECT snapshot_json, version, tenant_id, routing_epoch FROM agentlink_workflow WHERE context_id = ?",
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json"))
                        .restore(resultSet.getLong("version"), resultSet.getString("tenant_id"),
                                resultSet.getLong("routing_epoch")),
                contextId
        ).stream().findFirst();
    }

    @Override
    public Optional<Workflow> findForTenant(String tenantId, String contextId) {
        return jdbcTemplate.query(
                """
                SELECT snapshot_json, version, tenant_id, routing_epoch
                FROM agentlink_workflow WHERE tenant_id = ? AND context_id = ?
                """,
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json"))
                        .restore(resultSet.getLong("version"), resultSet.getString("tenant_id"),
                                resultSet.getLong("routing_epoch")),
                tenantId, contextId
        ).stream().findFirst();
    }

    @Override
    public Optional<Workflow> findByTaskId(String taskId) {
        return jdbcTemplate.query(
                """
                SELECT w.snapshot_json, w.version, w.tenant_id, w.routing_epoch
                FROM agentlink_task t
                JOIN agentlink_workflow w ON w.context_id = t.context_id
                WHERE t.task_id = ?
                """,
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json"))
                        .restore(resultSet.getLong("version"), resultSet.getString("tenant_id"),
                                resultSet.getLong("routing_epoch")),
                taskId
        ).stream().findFirst();
    }

    @Override
    public Optional<Workflow> findByTaskIdForTenant(String tenantId, String taskId) {
        return jdbcTemplate.query(
                """
                SELECT w.snapshot_json, w.version, w.tenant_id, w.routing_epoch
                FROM agentlink_task t
                JOIN agentlink_workflow w ON w.context_id = t.context_id
                WHERE t.tenant_id = ? AND t.task_id = ? AND w.tenant_id = ?
                """,
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json"))
                        .restore(resultSet.getLong("version"), resultSet.getString("tenant_id"),
                                resultSet.getLong("routing_epoch")),
                tenantId, taskId, tenantId
        ).stream().findFirst();
    }

    @Override
    public List<Workflow> findNonTerminal() {
        return jdbcTemplate.query(
                """
                SELECT w.snapshot_json, w.version, w.tenant_id, w.routing_epoch
                FROM agentlink_workflow w
                WHERE NOT EXISTS (
                    SELECT 1 FROM agentlink_task t WHERE t.context_id = w.context_id
                ) OR EXISTS (
                    SELECT 1 FROM agentlink_task t
                    WHERE t.context_id = w.context_id
                      AND t.state NOT IN ('COMPLETED', 'FAILED', 'CANCELED', 'REJECTED')
                )
                ORDER BY w.created_at
                """,
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json"))
                        .restore(resultSet.getLong("version"), resultSet.getString("tenant_id"),
                                resultSet.getLong("routing_epoch"))
        ).stream()
                .filter(workflow -> workflow.tasks().stream().anyMatch(task -> !task.state().terminal()))
                .toList();
    }

    @Transactional
    public int backfillTaskIndex() {
        List<Workflow> missing = jdbcTemplate.query(
                """
                SELECT w.snapshot_json, w.version, w.tenant_id, w.routing_epoch
                FROM agentlink_workflow w
                WHERE NOT EXISTS (
                    SELECT 1 FROM agentlink_task t WHERE t.context_id = w.context_id
                )
                ORDER BY w.created_at
                """,
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json"))
                        .restore(resultSet.getLong("version"), resultSet.getString("tenant_id"),
                                resultSet.getLong("routing_epoch"))
        );
        missing.forEach(this::syncTasks);
        return missing.size();
    }

    private void syncTasks(Workflow workflow) {
        for (AgentTask task : workflow.tasks()) {
            String spec = serializeValue(task.spec());
            String artifact = task.artifact() == null ? null : serializeValue(task.artifact());
            int updated = jdbcTemplate.update(
                    """
                    UPDATE agentlink_task
                    SET state = ?, agent_id = ?, artifact_id = ?, error = ?, spec_json = ?,
                        artifact_json = ?, updated_at = ?
                    WHERE task_id = ? AND tenant_id = ?
                    """,
                    task.state().name(), task.agentId(),
                    task.artifact() == null ? null : task.artifact().id(), task.error(), spec, artifact,
                    Timestamp.from(task.updatedAt()), task.id(), workflow.tenantId()
            );
            if (updated == 0) {
                jdbcTemplate.update(
                        """
                        INSERT INTO agentlink_task
                            (task_id, context_id, tenant_id, node_id, skill, state, agent_id, artifact_id,
                             error, spec_json, artifact_json, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        task.id(), workflow.contextId(), workflow.tenantId(),
                        task.spec().nodeId(), task.spec().skill(),
                        task.state().name(), task.agentId(),
                        task.artifact() == null ? null : task.artifact().id(), task.error(), spec, artifact,
                        Timestamp.from(task.createdAt()), Timestamp.from(task.updatedAt())
                );
            }
        }
    }

    private void writeOutbox(String tenantId, String aggregateId, String eventType, String payload) {
        jdbcTemplate.update(
                """
                INSERT INTO agentlink_outbox
                    (tenant_id, aggregate_type, aggregate_id, event_type, payload_json, created_at)
                VALUES (?, 'workflow', ?, ?, ?, ?)
                """,
                tenantId, aggregateId, eventType, payload, Timestamp.from(Instant.now())
        );
    }

    private String definitionHash(Workflow workflow) {
        try {
            String definition = objectMapper.writeValueAsString(
                    WorkflowSnapshot.from(workflow).tasks().stream().map(WorkflowSnapshot.TaskSnapshot::spec).toList()
            );
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(workflow.goal().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(definition.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("failed to hash workflow definition", failure);
        }
    }

    private String serialize(WorkflowSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to serialize workflow snapshot", failure);
        }
    }

    private WorkflowSnapshot deserialize(String json) {
        try {
            return objectMapper.readValue(json, WorkflowSnapshot.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to deserialize workflow snapshot", failure);
        }
    }

    private String serializeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to serialize workflow value", failure);
        }
    }
}
