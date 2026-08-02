package io.agentlink.api;

import jakarta.validation.constraints.NotBlank;

public record DiscoverAgentRequest(@NotBlank String cardUrl, String id) {
}
