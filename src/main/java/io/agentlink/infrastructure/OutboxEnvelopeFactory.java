package io.agentlink.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.OutboxMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OutboxEnvelopeFactory {
    private final ObjectMapper objectMapper;

    public OutboxEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(OutboxMessage message) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", message.id());
            envelope.put("tenantId", message.tenantId());
            envelope.put("aggregateType", message.aggregateType());
            envelope.put("aggregateId", message.aggregateId());
            envelope.put("eventType", message.eventType());
            envelope.put("createdAt", message.createdAt());
            envelope.put("payload", objectMapper.readTree(message.payloadJson()));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("failed to serialize outbox envelope", failure);
        }
    }
}
