package io.agentlink.infrastructure;

import io.agentlink.application.OutboxDeadLetter;
import io.agentlink.application.OutboxDeadLetterService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcOutboxDeadLetterService implements OutboxDeadLetterService {
    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxDeadLetterService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OutboxDeadLetter> list(String tenantId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT outbox_id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json,
                       attempt_count, dead_letter_reason, created_at, dead_lettered_at
                FROM agentlink_outbox
                WHERE tenant_id = ? AND dead_lettered_at IS NOT NULL
                ORDER BY dead_lettered_at DESC, outbox_id DESC
                LIMIT ?
                """,
                (resultSet, row) -> new OutboxDeadLetter(
                        resultSet.getLong("outbox_id"), resultSet.getString("tenant_id"),
                        resultSet.getString("aggregate_type"), resultSet.getString("aggregate_id"),
                        resultSet.getString("event_type"), resultSet.getString("payload_json"),
                        resultSet.getInt("attempt_count"), resultSet.getString("dead_letter_reason"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("dead_lettered_at").toInstant()
                ),
                tenantId, limit
        );
    }

    @Override
    public boolean replay(String tenantId, long outboxId) {
        return jdbcTemplate.update(
                """
                UPDATE agentlink_outbox
                SET dead_lettered_at = NULL, dead_letter_reason = NULL, last_error = NULL,
                    attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP,
                    claimed_by = NULL, claimed_until = NULL
                WHERE tenant_id = ? AND outbox_id = ? AND published_at IS NULL
                  AND dead_lettered_at IS NOT NULL
                """,
                tenantId, outboxId
        ) == 1;
    }
}
