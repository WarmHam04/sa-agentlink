package io.agentlink.application;

import io.agentlink.domain.AgentCard;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.RuntimeEvent;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface AgentInvoker {
    CompletionStage<Artifact> invoke(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink
    );
}
