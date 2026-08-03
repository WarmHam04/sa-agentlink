package io.agentlink.application;

import io.agentlink.domain.AgentCard;

import java.util.List;
import java.util.Optional;
import java.time.Duration;

public interface AgentDirectory {
    void register(AgentCard card);
    Optional<AgentCard> select(String skill);
    Optional<AgentCard> findById(String agentId);
    List<AgentCard> list();

    default void register(String tenantId, AgentCard card) {
        register(card);
    }

    default Optional<AgentCard> select(String tenantId, String skill) {
        return select(skill);
    }

    default Optional<AgentCard> findById(String tenantId, String agentId) {
        return findById(agentId);
    }

    default List<AgentCard> list(String tenantId) {
        return list();
    }

    default boolean heartbeat(String agentId, boolean healthy, int load, Duration ttl) {
        return false;
    }

    default boolean unregister(String agentId) {
        return false;
    }

    default boolean heartbeat(String tenantId, String agentId, boolean healthy, int load, Duration ttl) {
        return heartbeat(agentId, healthy, load, ttl);
    }

    default boolean unregister(String tenantId, String agentId) {
        return unregister(agentId);
    }
}
