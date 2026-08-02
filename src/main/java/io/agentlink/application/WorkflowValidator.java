package io.agentlink.application;

import io.agentlink.domain.TaskNodeSpec;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowValidator {
    public void validate(List<TaskNodeSpec> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("workflow must contain at least one task node");
        }

        Map<String, TaskNodeSpec> byId = new HashMap<>();
        for (TaskNodeSpec node : nodes) {
            if (node.nodeId().isBlank() || node.skill().isBlank() || node.instruction().isBlank()) {
                throw new IllegalArgumentException("nodeId, skill and instruction must not be blank");
            }
            if (byId.put(node.nodeId(), node) != null) {
                throw new IllegalArgumentException("duplicate nodeId: " + node.nodeId());
            }
        }

        nodes.forEach(node -> node.dependsOn().forEach(dependency -> {
            if (!byId.containsKey(dependency)) {
                throw new IllegalArgumentException("unknown dependency " + dependency + " for node " + node.nodeId());
            }
            if (dependency.equals(node.nodeId())) {
                throw new IllegalArgumentException("node cannot depend on itself: " + node.nodeId());
            }
        }));

        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (TaskNodeSpec node : nodes) {
            detectCycle(node.nodeId(), byId, visited, visiting);
        }
    }

    private void detectCycle(
            String nodeId,
            Map<String, TaskNodeSpec> nodes,
            Set<String> visited,
            Set<String> visiting
    ) {
        if (visited.contains(nodeId)) {
            return;
        }
        if (!visiting.add(nodeId)) {
            throw new IllegalArgumentException("task graph contains a cycle at node: " + nodeId);
        }
        for (String dependency : nodes.get(nodeId).dependsOn()) {
            detectCycle(dependency, nodes, visited, visiting);
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
    }
}
