package io.agentlink.infrastructure;

import io.agentlink.application.OutboxMessage;
import io.agentlink.application.OutboxPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "agentlink.outbox.kafka.topic")
public class KafkaOutboxPublisher implements OutboxPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxEnvelopeFactory envelopes;
    private final String topic;
    private final Duration timeout;

    public KafkaOutboxPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxEnvelopeFactory envelopes,
            @Value("${agentlink.outbox.kafka.topic}") String topic,
            @Value("${agentlink.outbox.kafka.timeout:PT10S}") Duration timeout
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.envelopes = envelopes;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void publish(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, null, message.aggregateId(), envelopes.serialize(message), List.of(
                        new RecordHeader("agentlink-outbox-id", Long.toString(message.id())
                                .getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("agentlink-event-type", message.eventType()
                                .getBytes(StandardCharsets.UTF_8))
                )
        );
        try {
            kafkaTemplate.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Kafka outbox delivery failed", failure);
        }
    }
}
