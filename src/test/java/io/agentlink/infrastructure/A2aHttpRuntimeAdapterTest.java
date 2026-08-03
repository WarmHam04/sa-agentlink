package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentlink.application.A2aDiscoveryService;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskNodeSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.ArrayList;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class A2aHttpRuntimeAdapterTest {
    private HttpServer server;
    private String baseUrl;
    private ExecutorService executor;
    private final AtomicReference<String> traceparent = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/agent-card.json", exchange -> respond(exchange, """
                {
                  "name":"Remote Research Agent",
                  "supportedInterfaces":[{"url":"%s/a2a","protocolVersion":"1.0"}],
                  "skills":[{"id":"research","name":"Research","description":"Find evidence"}]
                }
                """.formatted(baseUrl)));
        server.createContext("/a2a/message:send", exchange -> {
            traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            respond(exchange, """
                {
                  "id":"remote-task-1",
                  "contextId":"remote-context",
                  "status":{"state":"TASK_STATE_COMPLETED"},
                  "artifacts":[{"artifactId":"artifact-1","parts":[{"text":"remote evidence"}]}]
                }
                """);
        });
        server.createContext("/a2a/message:stream", exchange -> respondSse(exchange, """
                event:status-update
                data:{"id":"remote-stream-task","status":{"state":"TASK_STATE_WORKING"},"artifacts":[]}

                event:status-update
                data:{"id":"remote-stream-task","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"parts":[{"text":"streamed evidence"}]}]}

                """));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void discoversCardAndInvokesRemoteA2aAgent() {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboundUrlPolicy urlPolicy = new OutboundUrlPolicy(true);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        A2aDiscoveryService discovery = new A2aDiscoveryService(directory, objectMapper, urlPolicy);
        AgentCard card = discovery.discover(baseUrl + "/.well-known/agent-card.json", "remote-research");

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        A2aHttpRuntimeAdapter adapter = new A2aHttpRuntimeAdapter(objectMapper, urlPolicy, telemetry, executor);
        Artifact artifact;
        var span = telemetry.getTracer("test").spanBuilder("parent").startSpan();
        try (var ignored = span.makeCurrent()) {
            artifact = adapter.invoke(
                    card,
                    "local-task",
                    new TaskNodeSpec("research", "research", "find evidence", List.of(), Map.of()),
                    List.of(),
                    event -> { }
            ).toCompletableFuture().join();
        } finally {
            span.end();
            tracerProvider.close();
        }

        assertThat(directory.findById("remote-research")).contains(card);
        assertThat(artifact.content()).isEqualTo("remote evidence");
        assertThat(artifact.metadata()).containsEntry("remoteTaskId", "remote-task-1");
        assertThat(traceparent.get()).startsWith("00-");
    }

    @Test
    void consumesOutboundA2aStatusStream() {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboundUrlPolicy urlPolicy = new OutboundUrlPolicy(true);
        A2aHttpRuntimeAdapter adapter = new A2aHttpRuntimeAdapter(
                objectMapper, urlPolicy, OpenTelemetry.noop(), executor
        );
        AgentCard card = new AgentCard(
                "streaming-agent", "Streaming Agent", baseUrl + "/a2a", "1.0",
                List.of(new AgentSkill("research", "research")), true, 10, true
        );
        var events = new ArrayList<String>();

        Artifact artifact = adapter.invoke(
                card, "local-stream-task",
                new TaskNodeSpec("research", "research", "stream evidence", List.of(), Map.of()),
                List.of(), event -> events.add(event.type() + ":" + event.message())
        ).toCompletableFuture().join();

        assertThat(artifact.content()).isEqualTo("streamed evidence");
        assertThat(artifact.metadata()).containsEntry("remoteTaskId", "remote-stream-task");
        assertThat(events).anyMatch(event -> event.equals("stream.status:TASK_STATE_WORKING"));
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respondSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
