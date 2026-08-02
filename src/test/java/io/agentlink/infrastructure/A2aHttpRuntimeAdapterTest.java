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

import static org.assertj.core.api.Assertions.assertThat;

class A2aHttpRuntimeAdapterTest {
    private HttpServer server;
    private String baseUrl;
    private ExecutorService executor;

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
        server.createContext("/a2a/message:send", exchange -> respond(exchange, """
                {
                  "id":"remote-task-1",
                  "contextId":"remote-context",
                  "status":{"state":"TASK_STATE_COMPLETED"},
                  "artifacts":[{"artifactId":"artifact-1","parts":[{"text":"remote evidence"}]}]
                }
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

        A2aHttpRuntimeAdapter adapter = new A2aHttpRuntimeAdapter(
                objectMapper, urlPolicy, executor
        );
        Artifact artifact = adapter.invoke(
                card,
                "local-task",
                new TaskNodeSpec("research", "research", "find evidence", List.of(), Map.of()),
                List.of(),
                ignored -> { }
        ).toCompletableFuture().join();

        assertThat(directory.findById("remote-research")).contains(card);
        assertThat(artifact.content()).isEqualTo("remote evidence");
        assertThat(artifact.metadata()).containsEntry("remoteTaskId", "remote-task-1");
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
