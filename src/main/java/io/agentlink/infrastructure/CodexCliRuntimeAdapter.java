package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskNodeSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class CodexCliRuntimeAdapter extends AbstractCliRuntimeAdapter {
    public CodexCliRuntimeAdapter(
            ObjectMapper objectMapper,
            CliExecutionPolicy policy,
            @Qualifier("agentProcessExecutor") Executor executor
    ) {
        super(objectMapper, policy, executor);
    }

    @Override
    public String runtime() {
        return "codex";
    }

    @Override
    protected List<String> command(TaskNodeSpec task, String prompt, Path workingDirectory) {
        String sandbox = task.metadata().getOrDefault("sandbox", "read-only");
        if (!List.of("read-only", "workspace-write").contains(sandbox)) {
            throw new IllegalArgumentException("Codex sandbox must be read-only or workspace-write");
        }
        List<String> command = new ArrayList<>(List.of(
                "codex", "exec", "--json", "--ephemeral", "--sandbox", sandbox,
                "--skip-git-repo-check", "-C", workingDirectory.toString()
        ));
        command.add(prompt);
        return command;
    }

    @Override
    protected void onJsonEvent(JsonNode event, AtomicReference<String> finalText, Consumer<RuntimeEvent> eventSink) {
        String type = event.path("type").asText("event");
        String message = "";
        if (type.equals("item.completed") && event.path("item").path("type").asText().equals("agent_message")) {
            message = event.path("item").path("text").asText("");
            if (!message.isBlank()) {
                finalText.set(message);
            }
        } else if (type.equals("turn.failed") || type.equals("error")) {
            message = firstText(event, "message", "error");
        }
        eventSink.accept(new RuntimeEvent(runtime(), type, abbreviate(message, 500), Map.of(), Instant.now()));
    }
}
