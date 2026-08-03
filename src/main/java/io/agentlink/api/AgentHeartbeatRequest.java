package io.agentlink.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AgentHeartbeatRequest(boolean healthy, @Min(0) @Max(100) int load) {
}
