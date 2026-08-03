package io.agentlink.api;

import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RegisterAgentRequest(
        @NotBlank String id,
        @NotBlank String name,
        @NotBlank String endpoint,
        String protocolVersion,
        @NotEmpty List<AgentSkill> skills,
        boolean healthy,
        @Min(0) @Max(100) int load,
        boolean streaming
) {
    public AgentCard toCard() {
        return new AgentCard(id, name, endpoint, protocolVersion, skills, healthy, load, streaming);
    }
}
