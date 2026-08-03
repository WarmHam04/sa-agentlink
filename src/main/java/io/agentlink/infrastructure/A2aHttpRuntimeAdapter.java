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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;

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
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class A2aHttpRuntimeAdapter implements AgentRuntimeAdapter {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final OpenTelemetry openTelemetry;
    private final ConcurrentMap<String, RemoteTask> activeRemoteTasks = new ConcurrentHashMap<>();

    public A2aHttpRuntimeAdapter(
            ObjectMapper objectMapper,
            OutboundUrlPolicy outboundUrlPolicy,
            OpenTelemetry openTelemetry,
            @Qualifier("agentProcessExecutor") Executor executor
    ) {
        this.objectMapper = objectMapper;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.openTelemetry = openTelemetry;
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

        boolean streaming = agent.streaming();
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(messageUri(agent.endpoint(), streaming))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/a2a+json")
                    .header("Accept", streaming
                            ? "text/event-stream" : "application/a2a+json, application/json")
                    .header("A2A-Version", agent.protocolVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));
            openTelemetry.getPropagators().getTextMapPropagator().inject(
                    Context.current(), builder, HttpRequest.Builder::header
            );
            request = builder.build();
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }

        eventSink.accept(RuntimeEvent.of(runtime(), "request.started", request.uri().toString()));
        if (streaming) {
            CompletionStage<Artifact> result = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenApply(response -> parseStream(agent, taskId, task, response, eventSink));
            result.whenComplete((ignored, failure) -> activeRemoteTasks.remove(taskId));
            return result;
        }
        CompletionStage<Artifact> result = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(agent, taskId, task, response, eventSink));
        result.whenComplete((ignored, failure) -> activeRemoteTasks.remove(taskId));
        return result;
    }

    @Override
    public boolean cancel(String taskId) {
        RemoteTask remote = activeRemoteTasks.remove(taskId);
        if (remote == null) {
            return false;
        }
        String encodedTaskId = URLEncoder.encode(remote.remoteTaskId(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        URI uri = outboundUrlPolicy.validate(remote.endpoint() + "/tasks/" + encodedTaskId + ":cancel");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/a2a+json")
                .header("A2A-Version", remote.protocolVersion())
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        openTelemetry.getPropagators().getTextMapPropagator().inject(
                Context.current(), builder, HttpRequest.Builder::header
        );
        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding());
        return true;
    }

    private Artifact parseStream(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            HttpResponse<Stream<String>> response,
            Consumer<RuntimeEvent> eventSink
    ) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AgentProcessException(runtime(), "A2A stream returned HTTP " + response.statusCode(), null);
        }
        AtomicReference<JsonNode> latest = new AtomicReference<>();
        try (Stream<String> lines = response.body()) {
            StringBuilder data = new StringBuilder();
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.isEmpty()) {
                    dispatchStreamEvent(agent, taskId, data, latest, eventSink);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).stripLeading());
                }
            }
            dispatchStreamEvent(agent, taskId, data, latest, eventSink);
        }
        JsonNode payload = latest.get();
        if (payload == null) {
            throw new AgentProcessException(runtime(), "A2A stream completed without a Task event", null);
        }
        return artifactFromPayload(agent, taskId, task, payload, eventSink);
    }

    private void dispatchStreamEvent(
            AgentCard agent,
            String taskId,
            StringBuilder data,
            AtomicReference<JsonNode> latest,
            Consumer<RuntimeEvent> eventSink
    ) {
        if (data.isEmpty()) {
            return;
        }
        String payload = data.toString();
        data.setLength(0);
        if (!"[DONE]".equals(payload)) {
            consumeStreamEvent(agent, taskId, payload, latest, eventSink);
        }
    }

    private void consumeStreamEvent(
            AgentCard agent,
            String localTaskId,
            String data,
            AtomicReference<JsonNode> latest,
            Consumer<RuntimeEvent> eventSink
    ) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode payload = root.has("result") ? root.path("result") : root;
            latest.set(payload);
            String state = payload.path("status").path("state").asText("TASK_STATE_WORKING");
            String remoteTaskId = payload.path("id").asText("");
            if (!remoteTaskId.isBlank()) {
                activeRemoteTasks.put(localTaskId, new RemoteTask(
                        baseEndpoint(agent.endpoint()), remoteTaskId, agent.protocolVersion()
                ));
            }
            eventSink.accept(new RuntimeEvent(
                    runtime(), "stream.status", state,
                    remoteTaskId.isBlank() ? Map.of("state", state)
                            : Map.of("state", state, "remoteTaskId", remoteTaskId),
                    Instant.now()
            ));
        } catch (Exception failure) {
            throw new AgentProcessException(runtime(), "invalid A2A stream event", failure);
        }
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
            return artifactFromPayload(agent, taskId, task, payload, eventSink);
        } catch (Exception failure) {
            throw new AgentProcessException(runtime(), "invalid A2A response", failure);
        }
    }

    private Artifact artifactFromPayload(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            JsonNode payload,
            Consumer<RuntimeEvent> eventSink
    ) {
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

    private URI messageUri(String endpoint, boolean streaming) {
        String normalized = baseEndpoint(endpoint);
        String operation = streaming ? "/message:stream" : "/message:send";
        return outboundUrlPolicy.validate(
                normalized.endsWith(operation) ? normalized : normalized + operation
        );
    }

    private String baseEndpoint(String endpoint) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (normalized.endsWith("/message:send")) {
            return normalized.substring(0, normalized.length() - "/message:send".length());
        }
        if (normalized.endsWith("/message:stream")) {
            return normalized.substring(0, normalized.length() - "/message:stream".length());
        }
        return normalized;
    }

    private record RemoteTask(String endpoint, String remoteTaskId, String protocolVersion) {
    }
}
