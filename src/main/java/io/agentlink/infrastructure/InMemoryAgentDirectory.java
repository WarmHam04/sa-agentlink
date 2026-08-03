package io.agentlink.infrastructure;

import io.agentlink.application.AgentDirectory;
import io.agentlink.domain.AgentCard;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentDirectory implements AgentDirectory {
    private final ConcurrentHashMap<String, AgentCard> agents = new ConcurrentHashMap<>();

    @Override
    public void register(AgentCard card) {
        agents.put(card.id(), card);
    }

    @Override
    public Optional<AgentCard> select(String skill) {
        return agents.values().stream()
                .filter(agent -> agent.supports(skill))
                .min(Comparator.comparingInt(AgentCard::load).thenComparing(AgentCard::id));
    }

    @Override
    public Optional<AgentCard> findById(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public List<AgentCard> list() {
        return agents.values().stream().sorted(Comparator.comparing(AgentCard::id)).toList();
    }

    @Override
    public boolean heartbeat(String agentId, boolean healthy, int load, Duration ttl) {
        return agents.computeIfPresent(agentId, (ignored, card) -> new AgentCard(
                card.id(), card.name(), card.endpoint(), card.protocolVersion(), card.skills(), healthy, load,
                card.streaming()
        )) != null;
    }

    @Override
    public boolean unregister(String agentId) {
        return agents.remove(agentId) != null;
    }
}
