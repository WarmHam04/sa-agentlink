package io.agentlink.infrastructure;

import io.agentlink.application.AgentDirectory;
import io.agentlink.application.AgentRegistrationConflictException;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class JdbcAgentDirectory implements AgentDirectory {
    private final JdbcTemplate jdbcTemplate;
    private final String instanceId;
    private final Duration registrationTtl;
    private final boolean postgres;

    public JdbcAgentDirectory(
            JdbcTemplate jdbcTemplate,
            @Value("${agentlink.cluster.instance-id:${random.uuid}}") String instanceId,
            @Value("${agentlink.cluster.agent-registration-ttl:PT45S}") Duration registrationTtl
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceId = instanceId;
        this.registrationTtl = registrationTtl;
        try (var connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            this.postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (Exception failure) {
            throw new IllegalStateException("failed to identify agent registry database", failure);
        }
    }

    @Override
    @Transactional
    public void register(AgentCard card) {
        register("_system", card);
    }

    @Override
    @Transactional
    public void register(String tenantId, AgentCard card) {
        Instant now = databaseNow();
        Instant until = now.plus(registrationTtl);
        int updated = jdbcTemplate.update(
                """
                UPDATE agentlink_agent_registry
                SET name = ?, endpoint = ?, protocol_version = ?, healthy = ?, load_value = ?, streaming = ?,
                    owner_instance_id = ?, lease_until = ?, revision = revision + 1, updated_at = ?
                WHERE agent_id = ? AND tenant_id = ?
                  AND (owner_instance_id = ? OR lease_until <= ?)
                """,
                card.name(), card.endpoint(), card.protocolVersion(), card.healthy(), card.load(), card.streaming(),
                instanceId, Timestamp.from(until), Timestamp.from(now), card.id(), tenantId,
                instanceId, Timestamp.from(now)
        );
        if (updated == 0) {
            if (!insert(tenantId, card, until, now)) {
                throw new AgentRegistrationConflictException(card.id());
            }
        }
        jdbcTemplate.update("DELETE FROM agentlink_agent_skill WHERE agent_id = ?", card.id());
        card.skills().forEach(skill -> jdbcTemplate.update(
                "INSERT INTO agentlink_agent_skill (agent_id, skill_name, description) VALUES (?, ?, ?)",
                card.id(), skill.name().toLowerCase(), skill.description()
        ));
    }

    @Override
    public Optional<AgentCard> select(String skill) {
        return select("_default", skill);
    }

    @Override
    public Optional<AgentCard> select(String tenantId, String skill) {
        return queryAccessible(
                """
                SELECT DISTINCT a.*
                FROM agentlink_agent_registry a
                JOIN agentlink_agent_skill s ON s.agent_id = a.agent_id
                WHERE LOWER(s.skill_name) = LOWER(?) AND a.healthy = TRUE
                  AND (a.tenant_id = ? OR a.tenant_id = '_system')
                  AND a.lease_until > CURRENT_TIMESTAMP
                  AND (a.owner_instance_id = ? OR a.endpoint LIKE 'http://%' OR a.endpoint LIKE 'https://%')
                ORDER BY a.load_value, a.agent_id
                """,
                skill, tenantId, instanceId
        ).stream().findFirst();
    }

    @Override
    public Optional<AgentCard> findById(String agentId) {
        return findById("_default", agentId);
    }

    @Override
    public Optional<AgentCard> findById(String tenantId, String agentId) {
        return queryAccessible(
                """
                SELECT a.* FROM agentlink_agent_registry a
                WHERE a.agent_id = ? AND a.healthy = TRUE AND a.lease_until > CURRENT_TIMESTAMP
                  AND (a.tenant_id = ? OR a.tenant_id = '_system')
                  AND (a.owner_instance_id = ? OR a.endpoint LIKE 'http://%' OR a.endpoint LIKE 'https://%')
                """,
                agentId, tenantId, instanceId
        ).stream().findFirst();
    }

    @Override
    public List<AgentCard> list() {
        return list("_default");
    }

    @Override
    public List<AgentCard> list(String tenantId) {
        return queryAccessible(
                """
                SELECT a.* FROM agentlink_agent_registry a
                WHERE a.lease_until > CURRENT_TIMESTAMP
                  AND (a.tenant_id = ? OR a.tenant_id = '_system')
                ORDER BY a.agent_id
                """,
                tenantId
        );
    }

    @Override
    public boolean heartbeat(String agentId, boolean healthy, int load, Duration ttl) {
        return heartbeat("_system", agentId, healthy, load, ttl);
    }

    @Override
    public boolean heartbeat(String tenantId, String agentId, boolean healthy, int load, Duration ttl) {
        Instant now = databaseNow();
        return jdbcTemplate.update(
                """
                UPDATE agentlink_agent_registry
                SET healthy = ?, load_value = ?, lease_until = ?, revision = revision + 1, updated_at = ?
                WHERE agent_id = ? AND tenant_id = ? AND owner_instance_id = ?
                """,
                healthy, load, Timestamp.from(now.plus(ttl)), Timestamp.from(now), agentId, tenantId, instanceId
        ) == 1;
    }

    @Override
    public boolean unregister(String agentId) {
        return unregister("_system", agentId);
    }

    @Override
    public boolean unregister(String tenantId, String agentId) {
        return jdbcTemplate.update(
                """
                DELETE FROM agentlink_agent_registry
                WHERE agent_id = ? AND tenant_id = ? AND owner_instance_id = ?
                """,
                agentId, tenantId, instanceId
        ) == 1;
    }

    @Scheduled(fixedDelayString = "${agentlink.cluster.agent-registry-prune-interval:30000}")
    public void pruneExpired() {
        jdbcTemplate.update("DELETE FROM agentlink_agent_registry WHERE lease_until <= CURRENT_TIMESTAMP");
    }

    @Scheduled(fixedDelayString = "${agentlink.cluster.agent-registry-renew-interval:15000}")
    public void renewOwned() {
        Instant now = databaseNow();
        jdbcTemplate.update(
                """
                UPDATE agentlink_agent_registry
                SET lease_until = ?, updated_at = ?
                WHERE owner_instance_id = ? AND lease_until > ?
                """,
                Timestamp.from(now.plus(registrationTtl)), Timestamp.from(now), instanceId, Timestamp.from(now)
        );
    }

    private boolean insert(String tenantId, AgentCard card, Instant until, Instant now) {
        String sql = """
                INSERT INTO agentlink_agent_registry
                    (agent_id, tenant_id, name, endpoint, protocol_version, healthy, load_value, streaming,
                     owner_instance_id, lease_until, revision, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """;
        Object[] arguments = {
                card.id(), tenantId, card.name(), card.endpoint(), card.protocolVersion(), card.healthy(), card.load(),
                card.streaming(), instanceId, Timestamp.from(until), Timestamp.from(now)
        };
        if (postgres) {
            return jdbcTemplate.update(sql + " ON CONFLICT (agent_id) DO NOTHING", arguments) == 1;
        }
        try {
            return jdbcTemplate.update(sql, arguments) == 1;
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    private List<AgentCard> queryAccessible(String sql, Object... arguments) {
        List<AgentRow> rows = jdbcTemplate.query(sql, (resultSet, row) -> new AgentRow(
                resultSet.getString("agent_id"), resultSet.getString("name"),
                resultSet.getString("endpoint"), resultSet.getString("protocol_version"),
                resultSet.getBoolean("healthy"), resultSet.getInt("load_value"),
                resultSet.getBoolean("streaming")
        ), arguments);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<AgentSkill>> skills = new LinkedHashMap<>();
        rows.forEach(row -> skills.put(row.id(), new ArrayList<>()));
        String placeholders = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        jdbcTemplate.query(
                "SELECT agent_id, skill_name, description FROM agentlink_agent_skill WHERE agent_id IN ("
                        + placeholders + ") ORDER BY agent_id, skill_name",
                (resultSet, row) -> new AgentSkillRow(
                        resultSet.getString("agent_id"),
                        resultSet.getString("skill_name"),
                        resultSet.getString("description")
                ),
                rows.stream().map(AgentRow::id).toArray()
        ).forEach(skill -> skills.get(skill.agentId()).add(new AgentSkill(skill.name(), skill.description())));
        return rows.stream().map(row -> new AgentCard(
                row.id(), row.name(), row.endpoint(), row.protocolVersion(),
                skills.get(row.id()), row.healthy(), row.load(), row.streaming()
        )).toList();
    }

    private Instant databaseNow() {
        Timestamp value = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return value == null ? Instant.now() : value.toInstant();
    }

    private record AgentRow(
            String id, String name, String endpoint, String protocolVersion,
            boolean healthy, int load, boolean streaming
    ) {
    }

    private record AgentSkillRow(String agentId, String name, String description) {
    }
}
