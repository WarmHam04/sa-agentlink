package io.agentlink.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Workflow {
    private final String tenantId;
    private final long routingEpoch;
    private final String contextId;
    private final String goal;
    private final Instant createdAt;
    private final Map<String, AgentTask> tasks;
    private long version;

    public Workflow(String contextId, String goal, List<TaskNodeSpec> nodes) {
        this("_default", 1L, contextId, goal, nodes);
    }

    public Workflow(String tenantId, long routingEpoch, String contextId, String goal, List<TaskNodeSpec> nodes) {
        this(tenantId, routingEpoch, contextId, goal, Instant.now(), createTasks(contextId, nodes), 0L);
    }

    private Workflow(
            String tenantId, long routingEpoch, String contextId, String goal,
            Instant createdAt, Map<String, AgentTask> tasks, long version
    ) {
        this.tenantId = tenantId;
        this.routingEpoch = routingEpoch;
        this.contextId = contextId;
        this.goal = goal;
        this.createdAt = createdAt;
        this.tasks = Map.copyOf(tasks);
        this.version = version;
    }

    public static Workflow restore(
            String tenantId,
            long routingEpoch,
            String contextId,
            String goal,
            Instant createdAt,
            List<AgentTask> tasks,
            long version
    ) {
        Map<String, AgentTask> byNode = new LinkedHashMap<>();
        tasks.forEach(task -> byNode.put(task.spec().nodeId(), task));
        return new Workflow(tenantId, routingEpoch, contextId, goal, createdAt, byNode, version);
    }

    private static Map<String, AgentTask> createTasks(String contextId, List<TaskNodeSpec> nodes) {
        Map<String, AgentTask> byNode = new LinkedHashMap<>();
        nodes.forEach(node -> byNode.put(node.nodeId(), new AgentTask(contextId, node)));
        return byNode;
    }

    public String tenantId() { return tenantId; }
    public long routingEpoch() { return routingEpoch; }
    public String contextId() { return contextId; }
    public String goal() { return goal; }
    public Instant createdAt() { return createdAt; }
    public AgentTask task(String nodeId) { return tasks.get(nodeId); }
    public Collection<AgentTask> tasks() { return tasks.values(); }
    public synchronized long version() { return version; }

    public synchronized void markPersisted(long expectedVersion, long persistedVersion) {
        if (version != expectedVersion || persistedVersion != expectedVersion + 1) {
            throw new IllegalStateException("invalid workflow version advance");
        }
        version = persistedVersion;
    }

    public boolean sameDefinition(String expectedGoal, List<TaskNodeSpec> expectedNodes) {
        if (!goal.equals(expectedGoal) || tasks.size() != expectedNodes.size()) {
            return false;
        }
        Map<String, TaskNodeSpec> expected = expectedNodes.stream()
                .collect(java.util.stream.Collectors.toMap(TaskNodeSpec::nodeId, node -> node));
        return tasks.values().stream().allMatch(task -> task.spec().equals(expected.get(task.spec().nodeId())));
    }
}
