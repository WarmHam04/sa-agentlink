package io.agentlink.domain;

import java.util.Objects;

public record AgentSkill(String name, String description) {
    public AgentSkill {
        Objects.requireNonNull(name, "skill name must not be null");
        description = description == null ? "" : description;
    }
}
