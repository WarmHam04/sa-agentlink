package io.agentlink.infrastructure;

import io.agentlink.application.OutboxMessage;
import io.agentlink.application.OutboxPublisher;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(Producer.class)
public class RocketMqOutboxPublisher implements OutboxPublisher {
    private final Producer producer;
    private final RocketMqMessageFactory messages;

    public RocketMqOutboxPublisher(Producer producer, RocketMqMessageFactory messages) {
        this.producer = producer;
        this.messages = messages;
    }

    @Override
    public void publish(OutboxMessage message) {
        try {
            producer.send(messages.create(message));
        } catch (Exception failure) {
            throw new IllegalStateException("RocketMQ outbox delivery failed", failure);
        }
    }
}
