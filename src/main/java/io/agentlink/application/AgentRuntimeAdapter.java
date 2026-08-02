package io.agentlink.application;

import io.agentlink.domain.AgentCard;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskNodeSpec;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Provider boundary between AgentLink's A2A semantics and a concrete agent runtime.
 * Implementations may use a CLI process, JSON-RPC app server, HTTP A2A endpoint, or SDK.
 */
public interface AgentRuntimeAdapter {
    String runtime();

    boolean supports(AgentCard card);

    CompletionStage<Artifact> invoke(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink
    );

    default boolean cancel(String taskId) {
        return false;
    }
}
