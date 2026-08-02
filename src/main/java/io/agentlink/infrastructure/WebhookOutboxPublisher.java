package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.OutboxMessage;
import io.agentlink.application.OutboxPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agentlink.outbox.webhook.url")
public class WebhookOutboxPublisher implements OutboxPublisher {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final URI endpoint;
    private final String secret;

    public WebhookOutboxPublisher(
            ObjectMapper objectMapper,
            OutboundUrlPolicy urlPolicy,
            @Value("${agentlink.outbox.webhook.url}") String endpoint,
            @Value("${agentlink.outbox.webhook.secret:}") String secret
    ) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
        this.endpoint = urlPolicy.validate(endpoint);
        this.secret = secret;
    }

    @Override
    public void publish(OutboxMessage message) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", message.id());
            envelope.put("aggregateType", message.aggregateType());
            envelope.put("aggregateId", message.aggregateId());
            envelope.put("eventType", message.eventType());
            envelope.put("createdAt", message.createdAt());
            envelope.put("payload", objectMapper.readTree(message.payloadJson()));
            byte[] body = objectMapper.writeValueAsBytes(envelope);
            RestClient.RequestBodySpec request = restClient.post().uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", Long.toString(message.id()));
            if (!secret.isBlank()) {
                request.header("X-AgentLink-Signature", "sha256=" + signature(body));
            }
            request.body(body).retrieve().toBodilessEntity();
        } catch (Exception failure) {
            throw new IllegalStateException("outbox webhook delivery failed", failure);
        }
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
