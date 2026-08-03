package io.agentlink.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.agentlink.infrastructure.AgentLinkTelemetry;
import io.opentelemetry.context.Scope;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentTask;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskEvent;
import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.TaskState;
import io.agentlink.domain.Workflow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.Duration;
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
    private final ConcurrentMap<String, WorkflowLease> activeLeases = new ConcurrentHashMap<>();
    private final WorkflowLeaseManager leaseManager;
    private final String instanceId;
    private final Duration leaseTtl;
    private final AgentLinkTelemetry telemetry;
    private final TenantControlPlane tenantControlPlane;
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
            MeterRegistry meterRegistry,
            WorkflowLeaseManager leaseManager,
            AgentLinkTelemetry telemetry,
            TenantControlPlane tenantControlPlane,
            @Value("${agentlink.cluster.instance-id:${random.uuid}}") String instanceId,
            @Value("${agentlink.cluster.workflow-lease-ttl:PT30S}") Duration leaseTtl
    ) {
        this.directory = directory;
        this.invoker = invoker;
        this.store = store;
        this.eventBus = eventBus;
        this.validator = validator;
        this.executor = executor;
        this.leaseManager = leaseManager;
        this.instanceId = instanceId;
        this.leaseTtl = leaseTtl;
        this.telemetry = telemetry;
        this.tenantControlPlane = tenantControlPlane;
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
        tenantControlPlane.policyForWrite(command.tenantId());
        Workflow existing = store.findForTenant(command.tenantId(), contextId).orElse(null);
        if (existing != null) {
            if (existing.sameDefinition(command.goal(), command.nodes())) {
                return WorkflowView.from(existing);
            }
            throw new IllegalArgumentException("contextId already exists with a different workflow definition");
        }
        long routingEpoch = tenantControlPlane.admitWorkflow(command.tenantId(), contextId);
        Workflow workflow = new Workflow(
                command.tenantId(), routingEpoch, contextId, command.goal(), command.nodes()
        );
        Workflow stored;
        try {
            stored = store.saveIfAbsent(workflow);
        } catch (RuntimeException failure) {
            tenantControlPlane.releaseWorkflow(command.tenantId(), contextId);
            throw failure;
        }
        if (stored != workflow) {
            if (stored.tenantId().equals(command.tenantId())
                    && stored.sameDefinition(command.goal(), command.nodes())) {
                return WorkflowView.from(stored);
            }
            if (!stored.tenantId().equals(command.tenantId())) {
                tenantControlPlane.releaseWorkflow(command.tenantId(), contextId);
            }
            throw new IllegalArgumentException("contextId already exists with a different workflow definition");
        }
        WorkflowLease lease = leaseManager.acquire(contextId, instanceId, leaseTtl)
                .orElseThrow(() -> new WorkflowLeaseUnavailableException(contextId));
        activeLeases.put(contextId, lease);
        telemetry.startWorkflow(workflow.tenantId(), contextId, workflow.goal(), workflow.tasks().size());
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

    public WorkflowView get(String tenantId, String contextId) {
        return store.findForTenant(tenantId, contextId).map(WorkflowView::from)
                .orElseThrow(() -> new WorkflowNotFoundException(contextId));
    }

    public WorkflowView getByTaskId(String taskId) {
        return store.findByTaskId(taskId).map(WorkflowView::from)
                .orElseThrow(() -> new WorkflowNotFoundException(taskId));
    }

    public WorkflowView getByTaskId(String tenantId, String taskId) {
        return store.findByTaskIdForTenant(tenantId, taskId).map(WorkflowView::from)
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

    public WorkflowView cancelByTaskId(String tenantId, String taskId) {
        Workflow workflow = store.findByTaskIdForTenant(tenantId, taskId)
                .orElseThrow(() -> new WorkflowNotFoundException(taskId));
        return cancel(tenantId, workflow.contextId());
    }

    public WorkflowView cancel(String tenantId, String contextId) {
        Workflow active = activeWorkflows.get(contextId);
        if (active != null && !active.tenantId().equals(tenantId)) {
            throw new WorkflowNotFoundException(contextId);
        }
        if (active == null && store.findForTenant(tenantId, contextId).isEmpty()) {
            throw new WorkflowNotFoundException(contextId);
        }
        return cancel(contextId);
    }

    public WorkflowView cancel(String contextId) {
        Workflow workflow = activeWorkflows.get(contextId);
        if (workflow == null) {
            workflow = store.find(contextId).orElseThrow(() -> new WorkflowNotFoundException(contextId));
        }
        WorkflowLease lease = activeLeases.get(contextId);
        if (lease == null) {
            lease = leaseManager.acquire(contextId, instanceId, leaseTtl)
                    .orElseThrow(() -> new WorkflowLeaseUnavailableException(contextId));
            activeLeases.put(contextId, lease);
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
                    telemetry.endTask(task.id(), TaskState.CANCELED, null);
                    taskCanceled.increment();
                    publish(task, "task canceled");
                }
            }
            store.save(workflow, lease);
        }
        releaseWorkflow(workflow);
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
            if (failure != null && !task.state().terminal()) {
                String prefix = task.state() == TaskState.SUBMITTED ? "dependency failed: " : "";
                fail(workflow, task, prefix + rootMessage(failure));
            }
            if (workflow.tasks().stream().allMatch(candidate -> candidate.state().terminal())) {
                releaseWorkflow(workflow);
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
                ? directory.select(workflow.tenantId(), task.spec().skill())
                : directory.findById(workflow.tenantId(), preferredAgentId)
                        .filter(candidate -> candidate.supports(task.spec().skill())))
                .orElseThrow(() -> new IllegalStateException("no healthy agent supports skill: " + task.spec().skill()));

        task.assign(agent.id());
        task.transition(TaskState.WORKING, null);
        persist(workflow);
        publish(task, "delegated to " + agent.id());

        List<Artifact> inputs = new ArrayList<>();
        dependencies.forEach(future -> inputs.add(future.join()));

        CompletableFuture<Artifact> invocation;
        try (Scope ignored = telemetry.startTask(
                task.contextId(), task.id(), task.spec().nodeId(), task.spec().skill(), agent
        )) {
            invocation = invoker.invoke(
                            agent,
                            task.id(),
                            task.spec(),
                            List.copyOf(inputs),
                            event -> {
                                telemetry.runtimeEvent(task.id(), event);
                                publish(task, "runtime/" + event.runtime() + "/" + event.type()
                                        + ": " + event.message());
                            }
                    )
                    .toCompletableFuture();
        }
        return invocation
                .thenApply(artifact -> {
                    task.complete(artifact);
                    telemetry.endTask(task.id(), TaskState.COMPLETED, null);
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
            telemetry.endTask(task.id(), TaskState.FAILED, new IllegalStateException(message));
            taskFailed.increment();
            persist(workflow);
            publish(task, message);
        }
    }

    private void publish(AgentTask task, String message) {
        eventBus.publish(new TaskEvent(
                0L, workflowTenant(task.contextId()), task.contextId(), task.id(), task.spec().nodeId(), task.state(), message,
                task.artifact() == null ? null : task.artifact().id(), Instant.now()
        ));
    }

    private String workflowTenant(String contextId) {
        Workflow active = activeWorkflows.get(contextId);
        if (active != null) {
            return active.tenantId();
        }
        return store.find(contextId).map(Workflow::tenantId).orElse("_default");
    }

    private void persist(Workflow workflow) {
        synchronized (workflow) {
            WorkflowLease lease = activeLeases.get(workflow.contextId());
            if (lease == null) {
                store.save(workflow);
            } else {
                store.save(workflow, lease);
            }
        }
    }

    @Scheduled(fixedDelayString = "${agentlink.cluster.workflow-lease-renew-interval:10000}")
    public void renewLeases() {
        activeLeases.forEach((contextId, lease) -> leaseManager.renew(lease, leaseTtl)
                .ifPresentOrElse(
                        renewed -> {
                            activeLeases.replace(contextId, lease, renewed);
                            Workflow workflow = activeWorkflows.get(contextId);
                            if (workflow != null) {
                                tenantControlPlane.renewWorkflow(
                                        workflow.tenantId(), workflow.contextId(), leaseTtl.multipliedBy(2)
                                );
                            }
                        },
                        () -> abandonWorkflow(contextId, lease)
                ));
    }

    private void abandonWorkflow(String contextId, WorkflowLease lease) {
        if (!activeLeases.remove(contextId, lease)) {
            return;
        }
        telemetry.leaseLost(contextId, lease.fencingToken());
        Workflow workflow = activeWorkflows.remove(contextId);
        if (workflow != null) {
            workflow.tasks().forEach(task -> {
                invoker.cancel(task.id());
                CompletableFuture<Artifact> future = activeTasks.remove(task.id());
                if (future != null) {
                    future.cancel(true);
                }
                telemetry.endTask(task.id(), TaskState.FAILED,
                        new WorkflowLeaseLostException(contextId, lease.fencingToken()));
            });
        }
    }

    private void releaseWorkflow(Workflow workflow) {
        boolean wasActive = activeWorkflows.remove(workflow.contextId(), workflow);
        WorkflowLease lease = activeLeases.remove(workflow.contextId());
        if (lease != null) {
            leaseManager.release(lease);
        }
        if (wasActive || workflow.tasks().stream().allMatch(task -> task.state().terminal())) {
            tenantControlPlane.releaseWorkflow(workflow.tenantId(), workflow.contextId());
        }
        telemetry.endWorkflow(workflow.contextId(), workflowOutcome(workflow));
    }

    private TaskState workflowOutcome(Workflow workflow) {
        if (workflow.tasks().stream().anyMatch(task -> task.state() == TaskState.FAILED)) {
            return TaskState.FAILED;
        }
        if (workflow.tasks().stream().anyMatch(task -> task.state() == TaskState.REJECTED)) {
            return TaskState.REJECTED;
        }
        if (workflow.tasks().stream().anyMatch(task -> task.state() == TaskState.CANCELED)) {
            return TaskState.CANCELED;
        }
        return TaskState.COMPLETED;
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
