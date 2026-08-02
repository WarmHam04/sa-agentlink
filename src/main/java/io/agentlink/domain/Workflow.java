package io.agentlink.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Workflow {
    private final String contextId;
    private final String goal;
    private final Instant createdAt = Instant.now();
    private final Map<String, AgentTask> tasks;

    public Workflow(String contextId, String goal, List<TaskNodeSpec> nodes) {
        this.contextId = contextId;
        this.goal = goal;
        Map<String, AgentTask> byNode = new LinkedHashMap<>();
        nodes.forEach(node -> byNode.put(node.nodeId(), new AgentTask(contextId, node)));
        this.tasks = Map.copyOf(byNode);
    }

    public String contextId() { return contextId; }
    public String goal() { return goal; }
    public Instant createdAt() { return createdAt; }
    public AgentTask task(String nodeId) { return tasks.get(nodeId); }
    public Collection<AgentTask> tasks() { return tasks.values(); }

    public boolean sameDefinition(String expectedGoal, List<TaskNodeSpec> expectedNodes) {
        if (!goal.equals(expectedGoal) || tasks.size() != expectedNodes.size()) {
            return false;
        }
        Map<String, TaskNodeSpec> expected = expectedNodes.stream()
                .collect(java.util.stream.Collectors.toMap(TaskNodeSpec::nodeId, node -> node));
        return tasks.values().stream().allMatch(task -> task.spec().equals(expected.get(task.spec().nodeId())));
    }
}
