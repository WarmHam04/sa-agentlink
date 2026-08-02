package io.agentlink.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TaskNodeSpec(
        String nodeId,
        String skill,
        String instruction,
        List<String> dependsOn,
        Map<String, String> metadata
) {
    public TaskNodeSpec(String nodeId, String skill, String instruction, List<String> dependsOn) {
        this(nodeId, skill, instruction, dependsOn, Map.of());
    }

    public TaskNodeSpec {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(skill, "skill must not be null");
        Objects.requireNonNull(instruction, "instruction must not be null");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
