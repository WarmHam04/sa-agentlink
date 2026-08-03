package io.agentlink.infrastructure;

import io.agentlink.application.OutboxMessage;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;

import java.nio.charset.StandardCharsets;

public class RocketMqMessageFactory {
    private final ClientServiceProvider provider;
    private final OutboxEnvelopeFactory envelopes;
    private final String topic;

    public RocketMqMessageFactory(
            ClientServiceProvider provider,
            OutboxEnvelopeFactory envelopes,
            String topic
    ) {
        this.provider = provider;
        this.envelopes = envelopes;
        this.topic = topic;
    }

    public Message create(OutboxMessage message) {
        return provider.newMessageBuilder()
                .setTopic(topic)
                .setKeys(Long.toString(message.id()))
                .setTag(message.eventType().replace('.', '_'))
                .addProperty("agentlink-aggregate-id", message.aggregateId())
                .setBody(envelopes.serialize(message).getBytes(StandardCharsets.UTF_8))
                .build();
    }
}
