package io.agentlink.infrastructure;

import io.agentlink.application.AgentRuntimeAdapter;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.RuntimeEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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
public class LocalAgentInvoker implements AgentRuntimeAdapter {
    private final Executor executor;

    public LocalAgentInvoker(@Qualifier("agentExecutionExecutor") Executor executor) {
        this.executor = executor;
    }

    @Override
    public String runtime() {
        return "local";
    }

    @Override
    public boolean supports(AgentCard card) {
        return card.endpoint().startsWith("local://");
    }

    @Override
    public CompletionStage<Artifact> invoke(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink
    ) {
        return CompletableFuture.supplyAsync(() -> {
            eventSink.accept(RuntimeEvent.of(runtime(), "started", "local agent started"));
            String upstream = inputs.isEmpty()
                    ? "none"
                    : inputs.stream().map(Artifact::content).collect(Collectors.joining(" | "));
            String content = "agent=" + agent.name()
                    + "; skill=" + task.skill()
                    + "; instruction=" + task.instruction()
                    + "; upstream=[" + upstream + "]";
            Artifact artifact = new Artifact(
                    UUID.randomUUID().toString(), taskId, task.nodeId() + "-artifact",
                    "text/plain", content,
                    Map.of("agentId", agent.id(), "skill", task.skill()), Instant.now()
            );
            eventSink.accept(RuntimeEvent.of(runtime(), "completed", "local agent completed"));
            return artifact;
        }, executor);
    }
}
