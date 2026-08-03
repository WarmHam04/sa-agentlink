package io.agentlink.application;

import java.time.Instant;

public record WorkflowLease(String contextId, String ownerId, long fencingToken, Instant leaseUntil) {
}
