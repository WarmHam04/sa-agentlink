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
    private final ConcurrentHashMap<String, Registration> agents = new ConcurrentHashMap<>();

    @Override
    public void register(AgentCard card) {
        register("_system", card);
    }

    @Override
    public void register(String tenantId, AgentCard card) {
        agents.put(card.id(), new Registration(tenantId, card));
    }

    @Override
    public Optional<AgentCard> select(String skill) {
        return select("_default", skill);
    }

    @Override
    public Optional<AgentCard> select(String tenantId, String skill) {
        return agents.values().stream()
                .filter(registration -> visible(tenantId, registration))
                .map(Registration::card)
                .filter(agent -> agent.supports(skill))
                .min(Comparator.comparingInt(AgentCard::load).thenComparing(AgentCard::id));
    }

    @Override
    public Optional<AgentCard> findById(String agentId) {
        return findById("_default", agentId);
    }

    @Override
    public Optional<AgentCard> findById(String tenantId, String agentId) {
        return Optional.ofNullable(agents.get(agentId))
                .filter(registration -> visible(tenantId, registration))
                .map(Registration::card);
    }

    @Override
    public List<AgentCard> list() {
        return list("_default");
    }

    @Override
    public List<AgentCard> list(String tenantId) {
        return agents.values().stream()
                .filter(registration -> visible(tenantId, registration))
                .map(Registration::card)
                .sorted(Comparator.comparing(AgentCard::id)).toList();
    }

    @Override
    public boolean heartbeat(String agentId, boolean healthy, int load, Duration ttl) {
        return heartbeat("_system", agentId, healthy, load, ttl);
    }

    @Override
    public boolean heartbeat(String tenantId, String agentId, boolean healthy, int load, Duration ttl) {
        return agents.computeIfPresent(agentId, (ignored, registration) ->
                registration.tenantId().equals(tenantId) ? new Registration(tenantId, new AgentCard(
                        registration.card().id(), registration.card().name(), registration.card().endpoint(),
                        registration.card().protocolVersion(), registration.card().skills(), healthy, load,
                        registration.card().streaming()
                )) : registration) != null && agents.get(agentId).tenantId().equals(tenantId);
    }

    @Override
    public boolean unregister(String agentId) {
        return unregister("_system", agentId);
    }

    @Override
    public boolean unregister(String tenantId, String agentId) {
        Registration existing = agents.get(agentId);
        return existing != null && existing.tenantId().equals(tenantId) && agents.remove(agentId, existing);
    }

    private boolean visible(String tenantId, Registration registration) {
        return registration.tenantId().equals("_system") || registration.tenantId().equals(tenantId);
    }

    private record Registration(String tenantId, AgentCard card) {
    }
}
