package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.AgentRuntimeAdapter;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskNodeSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeAdapterContractTest {
    @TempDir
    Path workspace;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void codexAdapterUsesJsonlAndExtractsFinalAgentMessage() throws Exception {
        CodexCliRuntimeAdapter adapter = new CodexCliRuntimeAdapter(
                objectMapper, policy(), Runnable::run
        );
        TaskNodeSpec task = task(Map.of("sandbox", "read-only"));

        List<String> command = adapter.command(task, "review this repo", workspace);
        assertThat(command).containsSubsequence("codex", "exec", "--json", "--ephemeral", "--sandbox", "read-only");
        assertThat(command).doesNotContain("sh", "bash", "zsh");

        AtomicReference<String> finalText = new AtomicReference<>("");
        List<RuntimeEvent> events = new ArrayList<>();
        adapter.onJsonEvent(objectMapper.readTree("""
                {"type":"item.completed","item":{"type":"agent_message","text":"review complete"}}
                """), finalText, events::add);

        assertThat(finalText.get()).isEqualTo("review complete");
        assertThat(events).extracting(RuntimeEvent::type).containsExactly("item.completed");
    }

    @Test
    void providerAdaptersDefaultToReadOnlyOrPlanModes() {
        TaskNodeSpec task = task(Map.of());
        GeminiCliRuntimeAdapter gemini = new GeminiCliRuntimeAdapter(objectMapper, policy(), Runnable::run);
        ClaudeCliRuntimeAdapter claude = new ClaudeCliRuntimeAdapter(objectMapper, policy(), Runnable::run);
        AgyCliRuntimeAdapter agy = new AgyCliRuntimeAdapter(objectMapper, policy(), Runnable::run);

        assertThat(gemini.command(task, "analyze", workspace)).containsSubsequence("--approval-mode", "plan");
        assertThat(claude.command(task, "review", workspace)).containsSubsequence("--permission-mode", "plan");
        assertThat(agy.command(task, "review", workspace)).containsSubsequence("--mode", "plan");
    }

    @Test
    void providerParsersNormalizeStreamingAndTextResults() throws Exception {
        GeminiCliRuntimeAdapter gemini = new GeminiCliRuntimeAdapter(objectMapper, policy(), Runnable::run);
        ClaudeCliRuntimeAdapter claude = new ClaudeCliRuntimeAdapter(objectMapper, policy(), Runnable::run);
        AgyCliRuntimeAdapter agy = new AgyCliRuntimeAdapter(objectMapper, policy(), Runnable::run);
        AtomicReference<String> geminiText = new AtomicReference<>("");
        AtomicReference<String> claudeText = new AtomicReference<>("");
        AtomicReference<String> agyText = new AtomicReference<>("");

        gemini.onJsonEvent(objectMapper.readTree("""
                {"type":"message","role":"assistant","content":"first "}
                """), geminiText, ignored -> { });
        gemini.onJsonEvent(objectMapper.readTree("""
                {"type":"message","role":"assistant","content":"second"}
                """), geminiText, ignored -> { });
        claude.onJsonEvent(objectMapper.readTree("""
                {"type":"result","result":"claude result"}
                """), claudeText, ignored -> { });
        agy.onTextLine("line one", agyText, ignored -> { });
        agy.onTextLine("line two", agyText, ignored -> { });

        assertThat(geminiText.get()).isEqualTo("first second");
        assertThat(claudeText.get()).isEqualTo("claude result");
        assertThat(agyText.get()).isEqualTo("line one\nline two");
    }

    @Test
    void workspacePolicyRejectsDirectoryTraversal() {
        CliExecutionPolicy policy = policy();
        TaskNodeSpec escaping = task(Map.of("workingDirectory", "../outside"));

        assertThatThrownBy(() -> policy.workingDirectory(escaping))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void routingInvokerRetriesWithinConfiguredLimit() {
        AtomicInteger attempts = new AtomicInteger();
        AgentRuntimeAdapter flaky = new AgentRuntimeAdapter() {
            @Override public String runtime() { return "flaky"; }
            @Override public boolean supports(AgentCard card) { return true; }

            @Override
            public java.util.concurrent.CompletionStage<Artifact> invoke(
                    AgentCard agent,
                    String taskId,
                    TaskNodeSpec task,
                    List<Artifact> inputs,
                    java.util.function.Consumer<RuntimeEvent> eventSink
            ) {
                if (attempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(new IllegalStateException("transient"));
                }
                return CompletableFuture.completedFuture(new Artifact(
                        UUID.randomUUID().toString(), taskId, "result", "text/plain", "ok", Map.of(), Instant.now()
                ));
            }
        };
        RoutingAgentInvoker invoker = new RoutingAgentInvoker(List.of(flaky));
        AgentCard card = new AgentCard(
                "test", "Test", "test://agent", "1.0", List.of(new AgentSkill("coding", "")), true, 0
        );

        Artifact result = invoker.invoke(
                card, "task", task(Map.of("maxAttempts", "2")), List.of(), ignored -> { }
        ).toCompletableFuture().join();

        assertThat(result.content()).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    private CliExecutionPolicy policy() {
        return new CliExecutionPolicy(workspace.toString(), Duration.ofSeconds(5), 10_000);
    }

    private TaskNodeSpec task(Map<String, String> metadata) {
        return new TaskNodeSpec("node", "coding", "instruction", List.of(), metadata);
    }
}
