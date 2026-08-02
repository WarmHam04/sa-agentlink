package io.agentlink.infrastructure;

import io.agentlink.application.AgentDirectory;
import io.agentlink.domain.AgentCard;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
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
}
