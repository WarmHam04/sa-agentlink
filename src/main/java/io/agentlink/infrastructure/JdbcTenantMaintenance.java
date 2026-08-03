package io.agentlink.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcTenantMaintenance {
    private final JdbcTemplate jdbcTemplate;
    private final Duration rateBucketRetention;

    public JdbcTenantMaintenance(
            JdbcTemplate jdbcTemplate,
            @Value("${agentlink.tenancy.rate-bucket-retention:P2D}") Duration rateBucketRetention
    ) {
        this.jdbcTemplate = jdbcTemplate;
        if (rateBucketRetention.isZero() || rateBucketRetention.isNegative()) {
            throw new IllegalArgumentException("tenant rate-bucket retention must be positive");
        }
        this.rateBucketRetention = rateBucketRetention;
    }

    @Scheduled(fixedDelayString = "${agentlink.tenancy.maintenance-interval:3600000}")
    public void cleanup() {
        Instant now = databaseNow();
        jdbcTemplate.update(
                "DELETE FROM agentlink_tenant_workflow_reservation WHERE expires_at <= ?",
                Timestamp.from(now)
        );
        jdbcTemplate.update(
                "DELETE FROM agentlink_tenant_rate_bucket WHERE bucket_start < ?",
                Timestamp.from(now.minus(rateBucketRetention))
        );
    }

    private Instant databaseNow() {
        Timestamp value = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return value == null ? Instant.now() : value.toInstant();
    }
}
