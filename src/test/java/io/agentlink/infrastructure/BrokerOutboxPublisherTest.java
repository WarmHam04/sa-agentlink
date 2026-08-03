package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentlink.application.OutboxMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerOutboxPublisherTest {
    private final OutboxMessage message = new OutboxMessage(
            42L, "task", "task-1", "task.completed", "{\"result\":\"ok\"}", Instant.now()
    );

    @Test
    @SuppressWarnings("unchecked")
    void waitsForKafkaAcknowledgementAndCarriesIdempotencyHeaders() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new KafkaOutboxPublisher(
                template, envelopes(), "agentlink-events", Duration.ofSeconds(1)
        );

        publisher.publish(message);

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("agentlink-events");
        assertThat(record.key()).isEqualTo("task-1");
        assertThat(new String(record.headers().lastHeader("agentlink-outbox-id").value()))
                .isEqualTo("42");
        assertThat(record.value()).contains("\"result\":\"ok\"");
    }

    @Test
    void waitsForRocketMqSendReceipt() throws Exception {
        Producer producer = mock(Producer.class);
        RocketMqMessageFactory messages = mock(RocketMqMessageFactory.class);
        Message brokerMessage = mock(Message.class);
        when(messages.create(message)).thenReturn(brokerMessage);

        new RocketMqOutboxPublisher(producer, messages).publish(message);

        verify(producer).send(brokerMessage);
    }

    private OutboxEnvelopeFactory envelopes() {
        return new OutboxEnvelopeFactory(JsonMapper.builder().findAndAddModules().build());
    }
}
