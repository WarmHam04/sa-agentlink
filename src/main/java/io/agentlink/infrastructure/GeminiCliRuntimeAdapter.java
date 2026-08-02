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
public class GeminiCliRuntimeAdapter extends AbstractCliRuntimeAdapter {
    public GeminiCliRuntimeAdapter(
            ObjectMapper objectMapper,
            CliExecutionPolicy policy,
            @Qualifier("agentProcessExecutor") Executor executor
    ) {
        super(objectMapper, policy, executor);
    }

    @Override
    public String runtime() {
        return "gemini";
    }

    @Override
    protected List<String> command(TaskNodeSpec task, String prompt, Path workingDirectory) {
        String approvalMode = task.metadata().getOrDefault("approvalMode", "plan");
        if (!List.of("plan", "auto_edit").contains(approvalMode)) {
            throw new IllegalArgumentException("Gemini approvalMode must be plan or auto_edit");
        }
        return List.of(
                "gemini", "--prompt", prompt, "--output-format", "stream-json",
                "--approval-mode", approvalMode
        );
    }

    @Override
    protected void onJsonEvent(JsonNode event, AtomicReference<String> finalText, Consumer<RuntimeEvent> eventSink) {
        String type = event.path("type").asText("event");
        String message = firstText(event, "response", "message", "text", "result");
        if (type.equals("message") && event.path("role").asText().equals("assistant")) {
            message = firstText(event, "content", "text", "message");
            if (!message.isBlank()) {
                String chunk = message;
                finalText.updateAndGet(current -> current + chunk);
            }
        }
        if (type.equals("result") && !message.isBlank()) {
            finalText.set(message);
        }
        eventSink.accept(new RuntimeEvent(runtime(), type, abbreviate(message, 500), Map.of(), Instant.now()));
    }
}
