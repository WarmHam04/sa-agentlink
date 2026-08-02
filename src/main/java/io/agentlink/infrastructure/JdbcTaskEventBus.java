package io.agentlink.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.TaskEventBus;
import io.agentlink.domain.TaskEvent;
import io.agentlink.domain.TaskState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcTaskEventBus implements TaskEventBus {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public JdbcTaskEventBus(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TaskEvent publish(TaskEvent event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO agentlink_task_event
                        (context_id, task_id, node_id, state, message, artifact_id, event_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    new String[]{"event_offset"}
            );
            statement.setString(1, event.contextId());
            statement.setString(2, event.taskId());
            statement.setString(3, event.nodeId());
            statement.setString(4, event.state().name());
            statement.setString(5, event.message());
            statement.setString(6, event.artifactId());
            statement.setTimestamp(7, Timestamp.from(event.timestamp()));
            return statement;
        }, keyHolder);
        Number key = Objects.requireNonNull(keyHolder.getKey(), "database did not return event offset");
        TaskEvent persisted = event.withOffset(key.longValue());
        jdbcTemplate.update(
                """
                INSERT INTO agentlink_outbox
                    (aggregate_type, aggregate_id, event_type, payload_json, created_at)
                VALUES ('task', ?, 'task.event', ?, ?)
                """,
                event.taskId(), serialize(persisted), Timestamp.from(Instant.now())
        );
        subscribers.getOrDefault(event.contextId(), new CopyOnWriteArrayList<>())
                .removeIf(emitter -> !send(emitter, persisted));
        return persisted;
    }

    @Override
    public SseEmitter subscribe(String contextId, long afterOffset) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> contextSubscribers =
                subscribers.computeIfAbsent(contextId, ignored -> new CopyOnWriteArrayList<>());
        contextSubscribers.add(emitter);
        emitter.onCompletion(() -> contextSubscribers.remove(emitter));
        emitter.onTimeout(() -> contextSubscribers.remove(emitter));
        emitter.onError(ignored -> contextSubscribers.remove(emitter));
        replay(contextId, afterOffset).forEach(event -> send(emitter, event));
        return emitter;
    }

    public List<TaskEvent> replay(String contextId, long afterOffset) {
        return jdbcTemplate.query(
                """
                SELECT event_offset, context_id, task_id, node_id, state, message, artifact_id, event_time
                FROM agentlink_task_event
                WHERE context_id = ? AND event_offset > ?
                ORDER BY event_offset
                """,
                (resultSet, row) -> new TaskEvent(
                        resultSet.getLong("event_offset"),
                        resultSet.getString("context_id"),
                        resultSet.getString("task_id"),
                        resultSet.getString("node_id"),
                        TaskState.valueOf(resultSet.getString("state")),
                        resultSet.getString("message"),
                        resultSet.getString("artifact_id"),
                        resultSet.getTimestamp("event_time").toInstant()
                ),
                contextId, afterOffset
        );
    }

    private boolean send(SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.offset()))
                    .name("task-status")
                    .data(event));
            return true;
        } catch (IOException | IllegalStateException failure) {
            emitter.completeWithError(failure);
            return false;
        }
    }

    private String serialize(TaskEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to serialize task event", failure);
        }
    }
}
