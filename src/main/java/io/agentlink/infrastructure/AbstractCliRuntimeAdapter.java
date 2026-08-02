package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.application.AgentRuntimeAdapter;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskNodeSpec;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class AbstractCliRuntimeAdapter implements AgentRuntimeAdapter {
    private final ObjectMapper objectMapper;
    private final CliExecutionPolicy policy;
    private final Executor executor;
    private final ConcurrentMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    protected AbstractCliRuntimeAdapter(
            ObjectMapper objectMapper,
            CliExecutionPolicy policy,
            @Qualifier("agentProcessExecutor") Executor executor
    ) {
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.executor = executor;
    }

    @Override
    public boolean supports(AgentCard card) {
        return card.endpoint().equalsIgnoreCase("process://" + runtime());
    }

    @Override
    public CompletionStage<Artifact> invoke(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink
    ) {
        return CompletableFuture.supplyAsync(() -> executeProcess(agent, taskId, task, inputs, eventSink), executor);
    }

    @Override
    public boolean cancel(String taskId) {
        Process process = activeProcesses.get(taskId);
        if (process == null) {
            return false;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return true;
    }

    private Artifact executeProcess(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink
    ) {
        Path workingDirectory = policy.workingDirectory(task);
        String prompt = buildPrompt(task, inputs);
        List<String> command = command(task, prompt, workingDirectory);
        eventSink.accept(RuntimeEvent.of(runtime(), "process.started", "started " + command.get(0)));

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException failure) {
            throw new AgentProcessException(runtime(), "failed to start executable " + command.get(0), failure);
        }

        activeProcesses.put(taskId, process);
        try {
            return awaitProcess(agent, taskId, task, eventSink, process);
        } finally {
            activeProcesses.remove(taskId, process);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Artifact awaitProcess(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            Consumer<RuntimeEvent> eventSink,
            Process process
    ) {

        AtomicReference<String> finalText = new AtomicReference<>("");
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        CompletableFuture<Void> stdoutReader = CompletableFuture.runAsync(
                () -> readStdout(process, stdout, finalText, eventSink), executor
        );
        CompletableFuture<Void> stderrReader = CompletableFuture.runAsync(
                () -> readStream(process, stderr, true), executor
        );

        boolean finished;
        try {
            finished = process.waitFor(policy.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new AgentProcessException(runtime(), "agent execution interrupted", failure);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new AgentProcessException(runtime(), "agent execution timed out after " + policy.timeout(), null);
        }
        stdoutReader.join();
        stderrReader.join();
        if (process.exitValue() != 0) {
            throw new AgentProcessException(
                    runtime(),
                    "agent process exited with " + process.exitValue() + ": " + abbreviate(stderr.toString(), 2000),
                    null
            );
        }

        String content = finalText.get().isBlank() ? stdout.toString().trim() : finalText.get().trim();
        content = policy.limit(content);
        eventSink.accept(RuntimeEvent.of(runtime(), "process.completed", "agent process completed"));
        return new Artifact(
                UUID.randomUUID().toString(), taskId, task.nodeId() + "-artifact", "text/markdown", content,
                Map.of("agentId", agent.id(), "runtime", runtime(), "skill", task.skill()), Instant.now()
        );
    }

    protected abstract List<String> command(TaskNodeSpec task, String prompt, Path workingDirectory);

    protected void onJsonEvent(
            JsonNode event,
            AtomicReference<String> finalText,
            Consumer<RuntimeEvent> eventSink
    ) {
        String type = event.path("type").asText("event");
        String message = firstText(event, "message", "text", "response", "result");
        if (!message.isBlank()) {
            finalText.set(message);
        }
        eventSink.accept(new RuntimeEvent(runtime(), type, abbreviate(message, 500), Map.of(), Instant.now()));
    }

    protected final String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    protected final String abbreviate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private String buildPrompt(TaskNodeSpec task, List<Artifact> inputs) {
        if (inputs.isEmpty()) {
            return task.instruction();
        }
        String upstream = inputs.stream()
                .map(artifact -> "--- Artifact: " + artifact.name() + " ---\n" + artifact.content())
                .collect(Collectors.joining("\n\n"));
        return task.instruction() + "\n\nUse the following upstream artifacts as input:\n\n" + upstream;
    }

    private void readStdout(
            Process process,
            StringBuilder stdout,
            AtomicReference<String> finalText,
            Consumer<RuntimeEvent> eventSink
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append('\n');
                if (line.isBlank()) {
                    continue;
                }
                try {
                    onJsonEvent(objectMapper.readTree(line), finalText, eventSink);
                } catch (Exception ignored) {
                    onTextLine(line, finalText, eventSink);
                }
            }
        } catch (IOException failure) {
            throw new AgentProcessException(runtime(), "failed to read agent stdout", failure);
        }
    }

    protected void onTextLine(
            String line,
            AtomicReference<String> finalText,
            Consumer<RuntimeEvent> eventSink
    ) {
        finalText.set(line);
        eventSink.accept(RuntimeEvent.of(runtime(), "message", abbreviate(line, 500)));
    }

    private void readStream(Process process, StringBuilder destination, boolean stderr) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stderr ? process.getErrorStream() : process.getInputStream(), StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (destination.length() < 16_384) {
                    destination.append(line).append('\n');
                }
            }
        } catch (IOException failure) {
            throw new AgentProcessException(runtime(), "failed to read agent process stream", failure);
        }
    }
}
