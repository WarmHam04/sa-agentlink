package io.agentlink.infrastructure;

import io.agentlink.application.OutboxMessage;
import io.agentlink.application.OutboxPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcOutboxRelay {
    private final JdbcTemplate jdbcTemplate;
    private final List<OutboxPublisher> publishers;
    private final String instanceId;
    private final Duration claimTtl;
    private final boolean postgres;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    @Autowired
    public JdbcOutboxRelay(
            JdbcTemplate jdbcTemplate,
            List<OutboxPublisher> publishers,
            @Value("${agentlink.cluster.instance-id:${random.uuid}}") String instanceId,
            @Value("${agentlink.outbox.claim-ttl:PT30S}") Duration claimTtl,
            @Value("${agentlink.outbox.max-attempts:8}") int maxAttempts,
            @Value("${agentlink.outbox.initial-backoff:PT1S}") Duration initialBackoff,
            @Value("${agentlink.outbox.max-backoff:PT5M}") Duration maxBackoff
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.publishers = List.copyOf(publishers);
        this.instanceId = instanceId;
        this.claimTtl = claimTtl;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception failure) {
            throw new IllegalStateException("failed to identify outbox database", failure);
        }
    }

    JdbcOutboxRelay(JdbcTemplate jdbcTemplate, List<OutboxPublisher> publishers) {
        this(jdbcTemplate, publishers, "test-relay", Duration.ofSeconds(30),
                8, Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    @Scheduled(fixedDelayString = "${agentlink.outbox.poll-interval:1000}")
    public void relay() {
        if (publishers.isEmpty()) {
            return;
        }
        claimBatch().forEach(message -> {
            try {
                publishers.forEach(publisher -> publisher.publish(message));
                jdbcTemplate.update(
                        """
                        UPDATE agentlink_outbox
                        SET published_at = ?, claimed_by = NULL, claimed_until = NULL, last_error = NULL
                        WHERE outbox_id = ? AND published_at IS NULL AND claimed_by = ?
                        """,
                        Timestamp.from(databaseNow()), message.id(), instanceId
                );
            } catch (RuntimeException failure) {
                fail(message, failure);
            }
        });
    }

    private List<OutboxMessage> claimBatch() {
        return postgres ? claimPostgres() : claimPortable();
    }

    private List<OutboxMessage> claimPostgres() {
        Instant now = databaseNow();
        return jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT outbox_id FROM agentlink_outbox
                    WHERE published_at IS NULL
                      AND dead_lettered_at IS NULL
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                      AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP)
                    ORDER BY outbox_id
                    LIMIT 100
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE agentlink_outbox target
                SET claimed_by = ?, claimed_until = ?, attempt_count = attempt_count + 1
                FROM candidates
                WHERE target.outbox_id = candidates.outbox_id
                RETURNING target.outbox_id, target.aggregate_type, target.aggregate_id,
                          target.tenant_id, target.event_type, target.payload_json,
                          target.created_at, target.attempt_count
                """,
                rowMapper(), instanceId, Timestamp.from(now.plus(claimTtl))
        );
    }

    private List<OutboxMessage> claimPortable() {
        Instant now = databaseNow();
        List<OutboxMessage> candidates = jdbcTemplate.query(
                """
                SELECT outbox_id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json,
                       created_at, attempt_count
                FROM agentlink_outbox
                WHERE published_at IS NULL AND dead_lettered_at IS NULL AND next_attempt_at <= ?
                  AND (claimed_until IS NULL OR claimed_until <= ?)
                ORDER BY outbox_id
                LIMIT 100
                """,
                rowMapper(), Timestamp.from(now), Timestamp.from(now)
        );
        return candidates.stream().map(message -> {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE agentlink_outbox
                    SET claimed_by = ?, claimed_until = ?, attempt_count = attempt_count + 1
                    WHERE outbox_id = ? AND published_at IS NULL AND dead_lettered_at IS NULL
                      AND next_attempt_at <= ? AND (claimed_until IS NULL OR claimed_until <= ?)
                    """,
                    instanceId, Timestamp.from(now.plus(claimTtl)), message.id(),
                    Timestamp.from(now), Timestamp.from(now)
            );
            return updated == 1 ? withAttempt(message, message.attemptCount() + 1) : null;
        }).filter(Objects::nonNull).toList();
    }

    private RowMapper<OutboxMessage> rowMapper() {
        return (resultSet, row) -> new OutboxMessage(
                resultSet.getLong("outbox_id"), resultSet.getString("tenant_id"),
                resultSet.getString("aggregate_type"), resultSet.getString("aggregate_id"),
                resultSet.getString("event_type"), resultSet.getString("payload_json"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getInt("attempt_count")
        );
    }

    private OutboxMessage withAttempt(OutboxMessage message, int attemptCount) {
        return new OutboxMessage(
                message.id(), message.tenantId(), message.aggregateType(), message.aggregateId(),
                message.eventType(), message.payloadJson(), message.createdAt(), attemptCount
        );
    }

    private void fail(OutboxMessage message, RuntimeException failure) {
        Instant now = databaseNow();
        String error = abbreviate(failure.getMessage());
        if (message.attemptCount() >= maxAttempts) {
            jdbcTemplate.update(
                    """
                    UPDATE agentlink_outbox
                    SET claimed_by = NULL, claimed_until = NULL, last_error = ?,
                        dead_lettered_at = ?, dead_letter_reason = ?
                    WHERE outbox_id = ? AND published_at IS NULL AND claimed_by = ?
                    """,
                    error, Timestamp.from(now), error, message.id(), instanceId
            );
            return;
        }
        long multiplier = 1L << Math.min(30, Math.max(0, message.attemptCount() - 1));
        Duration delay;
        try {
            delay = initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            delay = maxBackoff;
        }
        if (delay.compareTo(maxBackoff) > 0) {
            delay = maxBackoff;
        }
        jdbcTemplate.update(
                """
                UPDATE agentlink_outbox
                SET claimed_by = NULL, claimed_until = NULL, last_error = ?, next_attempt_at = ?
                WHERE outbox_id = ? AND published_at IS NULL AND claimed_by = ?
                """,
                error, Timestamp.from(now.plus(delay)), message.id(), instanceId
        );
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "publisher failed without a message";
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private Instant databaseNow() {
        Timestamp value = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return value == null ? Instant.now() : value.toInstant();
    }
}
