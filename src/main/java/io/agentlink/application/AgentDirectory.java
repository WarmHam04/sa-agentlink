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

    default boolean heartbeat(String agentId, boolean healthy, int load, Duration ttl) {
        return false;
    }

    default boolean unregister(String agentId) {
        return false;
    }
}
