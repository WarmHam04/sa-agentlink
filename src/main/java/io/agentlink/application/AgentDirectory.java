package io.agentlink.application;

import io.agentlink.domain.AgentCard;

import java.util.List;
import java.util.Optional;

public interface AgentDirectory {
    void register(AgentCard card);
    Optional<AgentCard> select(String skill);
    Optional<AgentCard> findById(String agentId);
    List<AgentCard> list();
}
