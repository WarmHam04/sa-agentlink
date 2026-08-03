package io.agentlink.domain;

import java.util.List;
import java.util.Objects;

public record AgentCard(
        String id,
        String name,
        String endpoint,
        String protocolVersion,
        List<AgentSkill> skills,
        boolean healthy,
        int load,
        boolean streaming
) {
    public AgentCard(
            String id,
            String name,
            String endpoint,
            String protocolVersion,
            List<AgentSkill> skills,
            boolean healthy,
            int load
    ) {
        this(id, name, endpoint, protocolVersion, skills, healthy, load, false);
    }

    public AgentCard {
        Objects.requireNonNull(id, "agent id must not be null");
        Objects.requireNonNull(name, "agent name must not be null");
        Objects.requireNonNull(endpoint, "agent endpoint must not be null");
        protocolVersion = protocolVersion == null ? "1.0" : protocolVersion;
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (load < 0 || load > 100) {
            throw new IllegalArgumentException("load must be between 0 and 100");
        }
    }

    public boolean supports(String skill) {
        return healthy && skills.stream().anyMatch(candidate -> candidate.name().equalsIgnoreCase(skill));
    }
}
