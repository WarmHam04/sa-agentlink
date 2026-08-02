package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskNodeSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class ClaudeCliRuntimeAdapter extends AbstractCliRuntimeAdapter {
    public ClaudeCliRuntimeAdapter(
            ObjectMapper objectMapper,
            CliExecutionPolicy policy,
            @Qualifier("agentProcessExecutor") Executor executor
    ) {
        super(objectMapper, policy, executor);
    }

    @Override
    public String runtime() {
        return "claude";
    }

    @Override
    protected List<String> command(TaskNodeSpec task, String prompt, Path workingDirectory) {
        String permissionMode = task.metadata().getOrDefault("permissionMode", "plan");
        if (!List.of("plan", "acceptEdits").contains(permissionMode)) {
            throw new IllegalArgumentException("Claude permissionMode must be plan or acceptEdits");
        }
        return List.of(
                "claude", "--print", prompt,
                "--output-format", "stream-json",
                "--permission-mode", permissionMode,
                "--no-session-persistence",
                "--verbose"
        );
    }

    @Override
    protected void onJsonEvent(JsonNode event, AtomicReference<String> finalText, Consumer<RuntimeEvent> eventSink) {
        String type = event.path("type").asText("event");
        String message = firstText(event, "result", "message", "text");
        if (type.equals("assistant")) {
            JsonNode content = event.path("message").path("content");
            if (content.isArray()) {
                StringBuilder text = new StringBuilder();
                content.forEach(part -> {
                    if (part.path("type").asText().equals("text")) {
                        text.append(part.path("text").asText());
                    }
                });
                message = text.toString();
            }
            if (!message.isBlank()) {
                finalText.set(message);
            }
        }
        if (type.equals("result") && !message.isBlank()) {
            finalText.set(message);
        }
        eventSink.accept(new RuntimeEvent(runtime(), type, abbreviate(message, 500), Map.of(), Instant.now()));
    }
}
