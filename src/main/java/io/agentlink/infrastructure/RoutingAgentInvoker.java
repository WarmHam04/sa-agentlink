package io.agentlink.infrastructure;

import io.agentlink.application.AgentInvoker;
import io.agentlink.application.AgentRuntimeAdapter;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskNodeSpec;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

@Component
public class RoutingAgentInvoker implements AgentInvoker {
    private final List<AgentRuntimeAdapter> adapters;
    private final ConcurrentMap<String, AgentRuntimeAdapter> activeAdapters = new ConcurrentHashMap<>();
    private final java.util.Set<String> canceledTasks = ConcurrentHashMap.newKeySet();

    public RoutingAgentInvoker(List<AgentRuntimeAdapter> adapters) {
        this.adapters = adapters.stream().sorted(Comparator.comparing(AgentRuntimeAdapter::runtime)).toList();
    }

    @Override
    public CompletionStage<Artifact> invoke(
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink
    ) {
        AgentRuntimeAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.supports(agent))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no runtime adapter for endpoint: " + agent.endpoint()));
        eventSink.accept(RuntimeEvent.of(adapter.runtime(), "selected", "selected adapter " + adapter.runtime()));
        int maxAttempts = parseMaxAttempts(task);
        activeAdapters.put(taskId, adapter);
        CompletionStage<Artifact> invocation =
                invokeAttempt(adapter, agent, taskId, task, inputs, eventSink, 1, maxAttempts);
        invocation.whenComplete((ignored, failure) -> {
            activeAdapters.remove(taskId, adapter);
            canceledTasks.remove(taskId);
        });
        return invocation;
    }

    @Override
    public boolean cancel(String taskId) {
        canceledTasks.add(taskId);
        AgentRuntimeAdapter adapter = activeAdapters.get(taskId);
        if (adapter == null) {
            canceledTasks.remove(taskId);
            return false;
        }
        return adapter.cancel(taskId);
    }

    private CompletionStage<Artifact> invokeAttempt(
            AgentRuntimeAdapter adapter,
            AgentCard agent,
            String taskId,
            TaskNodeSpec task,
            List<Artifact> inputs,
            Consumer<RuntimeEvent> eventSink,
            int attempt,
            int maxAttempts
    ) {
        eventSink.accept(RuntimeEvent.of(adapter.runtime(), "attempt.started", attempt + "/" + maxAttempts));
        CompletableFuture<Artifact> result = new CompletableFuture<>();
        adapter.invoke(agent, taskId, task, inputs, eventSink).whenComplete((artifact, failure) -> {
            if (canceledTasks.contains(taskId)) {
                result.completeExceptionally(new CancellationException("task canceled"));
                return;
            }
            if (failure == null) {
                result.complete(artifact);
                return;
            }
            if (attempt >= maxAttempts) {
                result.completeExceptionally(unwrap(failure));
                return;
            }
            long delayMillis = Math.min(2_000L, 200L * (1L << (attempt - 1)));
            eventSink.accept(RuntimeEvent.of(
                    adapter.runtime(), "attempt.retrying", "retrying after " + delayMillis + "ms"
            ));
            CompletableFuture.runAsync(
                    () -> invokeAttempt(adapter, agent, taskId, task, inputs, eventSink, attempt + 1, maxAttempts)
                            .whenComplete((retriedArtifact, retriedFailure) -> {
                                if (retriedFailure == null) {
                                    result.complete(retriedArtifact);
                                } else {
                                    result.completeExceptionally(unwrap(retriedFailure));
                                }
                            }),
                    CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
            );
        });
        return result;
    }

    private int parseMaxAttempts(TaskNodeSpec task) {
        String configured = task.metadata().getOrDefault("maxAttempts", "1");
        try {
            int attempts = Integer.parseInt(configured);
            if (attempts < 1 || attempts > 5) {
                throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
            }
            return attempts;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("maxAttempts must be an integer", failure);
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
