package io.agentlink.application;

import io.agentlink.domain.TaskEvent;
import io.agentlink.domain.TaskState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;

@Component
@Order(10)
public class WorkflowRecoveryService implements ApplicationRunner {
    private final WorkflowStore store;
    private final TaskEventBus eventBus;
    private final boolean enabled;
    private final WorkflowLeaseManager leaseManager;
    private final String instanceId;
    private final Duration leaseTtl;

    public WorkflowRecoveryService(
            WorkflowStore store,
            TaskEventBus eventBus,
            WorkflowLeaseManager leaseManager,
            @Value("${agentlink.persistence.fail-interrupted-on-startup:true}") boolean enabled,
            @Value("${agentlink.cluster.instance-id:${random.uuid}}") String instanceId,
            @Value("${agentlink.cluster.workflow-lease-ttl:PT30S}") Duration leaseTtl
    ) {
        this.store = store;
        this.eventBus = eventBus;
        this.enabled = enabled;
        this.leaseManager = leaseManager;
        this.instanceId = instanceId;
        this.leaseTtl = leaseTtl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        store.findNonTerminal().forEach(workflow -> {
            WorkflowLease lease = leaseManager.acquire(workflow.contextId(), instanceId, leaseTtl).orElse(null);
            if (lease == null) {
                return;
            }
            try {
                synchronized (workflow) {
                    workflow.tasks().stream()
                            .filter(task -> !task.state().terminal())
                            .forEach(task -> {
                                String reason = "execution interrupted by service restart; safe replay requires a new task";
                                task.transition(TaskState.FAILED, reason);
                                eventBus.publish(new TaskEvent(
                                        0L, workflow.contextId(), task.id(), task.spec().nodeId(), task.state(),
                                        reason, null, Instant.now()
                                ));
                            });
                    store.save(workflow, lease);
                }
            } finally {
                leaseManager.release(lease);
            }
        });
    }
}
