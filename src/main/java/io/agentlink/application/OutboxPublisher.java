package io.agentlink.application;

/**
 * Delivery boundary for durable outbox messages. Implementations must be idempotent because
 * AgentLink deliberately provides at-least-once delivery.
 */
public interface OutboxPublisher {
    void publish(OutboxMessage message);
}
