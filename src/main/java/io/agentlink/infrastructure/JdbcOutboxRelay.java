package io.agentlink.infrastructure;

import io.agentlink.application.OutboxMessage;
import io.agentlink.application.OutboxPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcOutboxRelay {
    private final JdbcTemplate jdbcTemplate;
    private final List<OutboxPublisher> publishers;

    public JdbcOutboxRelay(JdbcTemplate jdbcTemplate, List<OutboxPublisher> publishers) {
        this.jdbcTemplate = jdbcTemplate;
        this.publishers = List.copyOf(publishers);
    }

    @Scheduled(fixedDelayString = "${agentlink.outbox.poll-interval:1000}")
    public void relay() {
        if (publishers.isEmpty()) {
            return;
        }
        pending().forEach(message -> {
            try {
                publishers.forEach(publisher -> publisher.publish(message));
                jdbcTemplate.update(
                        "UPDATE agentlink_outbox SET published_at = ? WHERE outbox_id = ? AND published_at IS NULL",
                        Timestamp.from(Instant.now()), message.id()
                );
            } catch (RuntimeException ignored) {
                // Keep the row unpublished so the next polling cycle retries it.
            }
        });
    }

    private List<OutboxMessage> pending() {
        return jdbcTemplate.query(
                """
                SELECT outbox_id, aggregate_type, aggregate_id, event_type, payload_json, created_at
                FROM agentlink_outbox
                WHERE published_at IS NULL
                ORDER BY outbox_id
                LIMIT 100
                """,
                (resultSet, row) -> new OutboxMessage(
                        resultSet.getLong("outbox_id"),
                        resultSet.getString("aggregate_type"),
                        resultSet.getString("aggregate_id"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload_json"),
                        resultSet.getTimestamp("created_at").toInstant()
                )
        );
    }
}
