package io.agentlink.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentTask;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskEvent;
import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.TaskState;
import io.agentlink.domain.Workflow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class WorkflowOrchestrator {
    private final AgentDirectory directory;
    private final AgentInvoker invoker;
    private final WorkflowStore store;
    private final TaskEventBus eventBus;
    private final WorkflowValidator validator;
    private final Executor executor;
    private final ConcurrentMap<String, Workflow> activeWorkflows = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Artifact>> activeTasks = new ConcurrentHashMap<>();
    private final Counter workflowCreated;
    private final Counter taskCompleted;
    private final Counter taskFailed;
    private final Counter taskCanceled;

    public WorkflowOrchestrator(
            AgentDirectory directory,
            AgentInvoker invoker,
            WorkflowStore store,
            TaskEventBus eventBus,
            WorkflowValidator validator,
            @Qualifier("agentExecutionExecutor") Executor executor,
            MeterRegistry meterRegistry
    ) {
        this.directory = directory;
        this.invoker = invoker;
        this.store = store;
        this.eventBus = eventBus;
        this.validator = validator;
        this.executor = executor;
        this.workflowCreated = meterRegistry.counter("agentlink.workflows.created");
        this.taskCompleted = meterRegistry.counter("agentlink.task.completed");
        this.taskFailed = meterRegistry.counter("agentlink.task.failed");
        this.taskCanceled = meterRegistry.counter("agentlink.task.canceled");
    }

    public WorkflowView create(CreateWorkflowCommand command) {
        validator.validate(command.nodes());
        String contextId = command.contextId() == null || command.contextId().isBlank()
                ? UUID.randomUUID().toString()
                : command.contextId();
        Workflow workflow = new Workflow(contextId, command.goal(), command.nodes());
        Workflow stored = store.saveIfAbsent(workflow);
        if (stored != workflow) {
            if (stored.sameDefinition(command.goal(), command.nodes())) {
                return WorkflowView.from(stored);
            }
            throw new IllegalArgumentException("contextId already exists with a different workflow definition");
        }
        workflow.tasks().forEach(task -> publish(task, "task submitted"));
        workflowCreated.increment();
        activeWorkflows.put(contextId, workflow);
        schedule(workflow);
        return WorkflowView.from(workflow);
    }

    public WorkflowView get(String contextId) {
        return store.find(contextId).map(WorkflowView::from)
                .orElseThrow(() -> new WorkflowNotFoundException(contextId));
    }

    public WorkflowView getByTaskId(String taskId) {
        return store.findByTaskId(taskId).map(WorkflowView::from)
                .orElseThrow(() -> new WorkflowNotFoundException(taskId));
    }

    public WorkflowView cancelByTaskId(String taskId) {
        Workflow workflow = activeWorkflows.values().stream()
                .filter(candidate -> candidate.tasks().stream().anyMatch(task -> task.id().equals(taskId)))
                .findFirst()
                .orElseGet(() -> store.findByTaskId(taskId)
                        .orElseThrow(() -> new WorkflowNotFoundException(taskId)));
        return cancel(workflow.contextId());
    }

    public WorkflowView cancel(String contextId) {
        Workflow workflow = activeWorkflows.get(contextId);
        if (workflow == null) {
            workflow = store.find(contextId).orElseThrow(() -> new WorkflowNotFoundException(contextId));
        }
        synchronized (workflow) {
            for (AgentTask task : workflow.tasks()) {
                if (!task.state().terminal()) {
                    invoker.cancel(task.id());
                    CompletableFuture<Artifact> future = activeTasks.remove(task.id());
                    if (future != null) {
                        future.cancel(true);
                    }
                    task.transition(TaskState.CANCELED, "canceled by coordinator");
                    taskCanceled.increment();
                    publish(task, "task canceled");
                }
            }
            store.save(workflow);
        }
        activeWorkflows.remove(contextId, workflow);
        return WorkflowView.from(workflow);
    }

    private void schedule(Workflow workflow) {
        Map<String, CompletableFuture<Artifact>> futures = new HashMap<>();
        workflow.tasks().forEach(task -> scheduleTask(workflow, task, futures));
    }

    private CompletableFuture<Artifact> scheduleTask(
            Workflow workflow,
            AgentTask task,
            Map<String, CompletableFuture<Artifact>> futures
    ) {
        CompletableFuture<Artifact> existing = futures.get(task.spec().nodeId());
        if (existing != null) {
            return existing;
        }

        List<CompletableFuture<Artifact>> dependencies = task.spec().dependsOn().stream()
                .map(workflow::task)
                .map(dependency -> scheduleTask(workflow, dependency, futures))
                .toList();

        CompletableFuture<Artifact> future = CompletableFuture
                .allOf(dependencies.toArray(CompletableFuture[]::new))
                .thenComposeAsync(ignored -> execute(workflow, task, dependencies), executor);

        activeTasks.put(task.id(), future);
        future.whenComplete((artifact, failure) -> {
            activeTasks.remove(task.id());
            if (failure != null && task.state() == TaskState.SUBMITTED) {
                fail(workflow, task, "dependency failed: " + rootMessage(failure));
            }
            if (workflow.tasks().stream().allMatch(candidate -> candidate.state().terminal())) {
                activeWorkflows.remove(workflow.contextId(), workflow);
            }
        });
        futures.put(task.spec().nodeId(), future);
        return future;
    }

    private CompletableFuture<Artifact> execute(
            Workflow workflow,
            AgentTask task,
            List<CompletableFuture<Artifact>> dependencies
    ) {
        String preferredAgentId = task.spec().metadata().get("agentId");
        AgentCard agent = (preferredAgentId == null
                ? directory.select(task.spec().skill())
                : directory.findById(preferredAgentId).filter(candidate -> candidate.supports(task.spec().skill())))
                .orElseThrow(() -> new IllegalStateException("no healthy agent supports skill: " + task.spec().skill()));

        task.assign(agent.id());
        task.transition(TaskState.WORKING, null);
        persist(workflow);
        publish(task, "delegated to " + agent.id());

        List<Artifact> inputs = new ArrayList<>();
        dependencies.forEach(future -> inputs.add(future.join()));

        return invoker.invoke(
                        agent,
                        task.id(),
                        task.spec(),
                        List.copyOf(inputs),
                        event -> publish(task, "runtime/" + event.runtime() + "/" + event.type() + ": " + event.message())
                )
                .toCompletableFuture()
                .thenApply(artifact -> {
                    task.complete(artifact);
                    taskCompleted.increment();
                    persist(workflow);
                    publish(task, "artifact produced");
                    return artifact;
                })
                .exceptionally(failure -> {
                    fail(workflow, task, rootMessage(failure));
                    throw new CompletionException(failure);
                });
    }

    private void fail(Workflow workflow, AgentTask task, String message) {
        if (!task.state().terminal()) {
            task.transition(TaskState.FAILED, message);
            taskFailed.increment();
            persist(workflow);
            publish(task, message);
        }
    }

    private void publish(AgentTask task, String message) {
        eventBus.publish(new TaskEvent(
                0L, task.contextId(), task.id(), task.spec().nodeId(), task.state(), message,
                task.artifact() == null ? null : task.artifact().id(), Instant.now()
        ));
    }

    private void persist(Workflow workflow) {
        synchronized (workflow) {
            store.save(workflow);
        }
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
