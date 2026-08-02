package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.domain.TaskNodeSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import io.agentlink.domain.RuntimeEvent;

@Component
public class AgyCliRuntimeAdapter extends AbstractCliRuntimeAdapter {
    public AgyCliRuntimeAdapter(
            ObjectMapper objectMapper,
            CliExecutionPolicy policy,
            @Qualifier("agentProcessExecutor") Executor executor
    ) {
        super(objectMapper, policy, executor);
    }

    @Override
    public String runtime() {
        return "agy";
    }

    @Override
    protected List<String> command(TaskNodeSpec task, String prompt, Path workingDirectory) {
        String mode = task.metadata().getOrDefault("mode", "plan");
        if (!List.of("plan", "accept-edits").contains(mode)) {
            throw new IllegalArgumentException("agy mode must be plan or accept-edits");
        }
        return List.of("agy", "--print", prompt, "--mode", mode, "--print-timeout", "10m");
    }

    @Override
    protected void onTextLine(
            String line,
            AtomicReference<String> finalText,
            Consumer<RuntimeEvent> eventSink
    ) {
        finalText.updateAndGet(current -> current.isBlank() ? line : current + "\n" + line);
        eventSink.accept(RuntimeEvent.of(runtime(), "message", abbreviate(line, 500)));
    }
}
