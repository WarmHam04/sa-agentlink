package io.agentlink.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.WorkflowSnapshot;
import io.agentlink.application.WorkflowStore;
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

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcWorkflowStore implements WorkflowStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Workflow saveIfAbsent(Workflow workflow) {
        String snapshot = serialize(WorkflowSnapshot.from(workflow));
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO agentlink_workflow
                        (context_id, goal, definition_hash, snapshot_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    workflow.contextId(), workflow.goal(), definitionHash(workflow), snapshot,
                    Timestamp.from(workflow.createdAt()), Timestamp.from(Instant.now())
            );
            writeOutbox(workflow.contextId(), "workflow.created", snapshot);
            return workflow;
        } catch (DuplicateKeyException duplicate) {
            return find(workflow.contextId()).orElseThrow(() -> duplicate);
        }
    }

    @Override
    @Transactional
    public void save(Workflow workflow) {
        String snapshot = serialize(WorkflowSnapshot.from(workflow));
        int updated = jdbcTemplate.update(
                "UPDATE agentlink_workflow SET snapshot_json = ?, updated_at = ? WHERE context_id = ?",
                snapshot, Timestamp.from(Instant.now()), workflow.contextId()
        );
        if (updated != 1) {
            throw new IllegalStateException("workflow disappeared during update: " + workflow.contextId());
        }
        writeOutbox(workflow.contextId(), "workflow.snapshot.updated", snapshot);
    }

    @Override
    public Optional<Workflow> find(String contextId) {
        return jdbcTemplate.query(
                "SELECT snapshot_json FROM agentlink_workflow WHERE context_id = ?",
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json")).restore(),
                contextId
        ).stream().findFirst();
    }

    @Override
    public Optional<Workflow> findByTaskId(String taskId) {
        return jdbcTemplate.query(
                "SELECT snapshot_json FROM agentlink_workflow ORDER BY created_at DESC",
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json")).restore()
        ).stream()
                .filter(workflow -> workflow.tasks().stream().anyMatch(task -> task.id().equals(taskId)))
                .findFirst();
    }

    @Override
    public List<Workflow> findNonTerminal() {
        return jdbcTemplate.query(
                "SELECT snapshot_json FROM agentlink_workflow ORDER BY created_at",
                (resultSet, row) -> deserialize(resultSet.getString("snapshot_json")).restore()
        ).stream()
                .filter(workflow -> workflow.tasks().stream().anyMatch(task -> !task.state().terminal()))
                .toList();
    }

    private void writeOutbox(String aggregateId, String eventType, String payload) {
        jdbcTemplate.update(
                """
                INSERT INTO agentlink_outbox
                    (aggregate_type, aggregate_id, event_type, payload_json, created_at)
                VALUES ('workflow', ?, ?, ?, ?)
                """,
                aggregateId, eventType, payload, Timestamp.from(Instant.now())
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
}
