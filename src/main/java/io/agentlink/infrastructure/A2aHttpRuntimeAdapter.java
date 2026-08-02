package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.AgentRuntimeAdapter;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskNodeSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class A2aHttpRuntimeAdapter implements AgentRuntimeAdapter {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final OutboundUrlPolicy outboundUrlPolicy;

    public A2aHttpRuntimeAdapter(
            ObjectMapper objectMapper,
            OutboundUrlPolicy outboundUrlPolicy,
            @Qualifier("agentProcessExecutor") Executor executor
    ) {
        this.objectMapper = objectMapper;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.httpClient = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String runtime() {
        return "a2a-http";
    }

    @Override
    public boolean supports(AgentCard card) {
        return card.endpoint().startsWith("http://") || card.endpoint().startsWith("https://");
    }

    @Override
    public CompletionStage<Artifact> invoke(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink
    ) {
        String prompt = task.instruction();
        if (!inputs.isEmpty()) {
            prompt += "\n\nUpstream artifacts:\n" + inputs.stream()
                    .map(artifact -> "--- " + artifact.name() + " ---\n" + artifact.content())
                    .collect(Collectors.joining("\n\n"));
        }

        Map<String, Object> requestBody = Map.of(
                "message", Map.of(
                        "messageId", UUID.randomUUID().toString(),
                        "role", "ROLE_USER",
                        "parts", List.of(Map.of("text", prompt))
                ),
                "configuration", Map.of("returnImmediately", false),
                "metadata", Map.of("agentlinkTaskId", taskId, "agentlinkNodeId", task.nodeId())
        );

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(messageSendUri(agent.endpoint()))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/a2a+json")
                    .header("Accept", "application/a2a+json, application/json")
                    .header("A2A-Version", agent.protocolVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }

        eventSink.accept(RuntimeEvent.of(runtime(), "request.started", request.uri().toString()));
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(agent, taskId, task, response, eventSink));
    }

    private Artifact parseResponse(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            HttpResponse<String> response,
            Consumer<RuntimeEvent> eventSink
    ) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AgentProcessException(runtime(), "A2A endpoint returned HTTP " + response.statusCode(), null);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode payload = root.has("result") ? root.path("result") : root;
            String content = extractContent(payload);
            String remoteTaskId = payload.path("id").asText("");
            eventSink.accept(new RuntimeEvent(
                    runtime(), "response.completed", "remote A2A task completed",
                    remoteTaskId.isBlank() ? Map.of() : Map.of("remoteTaskId", remoteTaskId), Instant.now()
            ));
            return new Artifact(
                    UUID.randomUUID().toString(), taskId, task.nodeId() + "-artifact", "text/markdown", content,
                    Map.of("agentId", agent.id(), "runtime", runtime(), "remoteTaskId", remoteTaskId), Instant.now()
            );
        } catch (Exception failure) {
            throw new AgentProcessException(runtime(), "invalid A2A response", failure);
        }
    }

    private String extractContent(JsonNode payload) {
        StringBuilder content = new StringBuilder();
        JsonNode artifacts = payload.path("artifacts");
        if (artifacts.isArray()) {
            artifacts.forEach(artifact -> appendParts(content, artifact.path("parts")));
        }
        if (content.isEmpty()) {
            appendParts(content, payload.path("parts"));
        }
        if (content.isEmpty() && payload.path("message").isObject()) {
            appendParts(content, payload.path("message").path("parts"));
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException("A2A response contains no text artifact or message");
        }
        return content.toString().trim();
    }

    private void appendParts(StringBuilder destination, JsonNode parts) {
        if (!parts.isArray()) {
            return;
        }
        parts.forEach(part -> {
            JsonNode text = part.get("text");
            if (text != null && text.isTextual()) {
                if (!destination.isEmpty()) {
                    destination.append('\n');
                }
                destination.append(text.asText());
            }
        });
    }

    private URI messageSendUri(String endpoint) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return outboundUrlPolicy.validate(
                normalized.endsWith("/message:send") ? normalized : normalized + "/message:send"
        );
    }
}
