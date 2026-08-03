package io.agentlink.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record TenantFailoverRequest(@NotBlank String homeRegion, @Min(1) long expectedRoutingEpoch) {
}
