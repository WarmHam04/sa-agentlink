package io.agentlink.api;

import io.agentlink.application.CreateWorkflowCommand;
import io.agentlink.domain.TaskNodeSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record CreateWorkflowRequest(
        String contextId,
        @NotBlank String goal,
        @NotEmpty List<@Valid NodeRequest> nodes
) {
    public CreateWorkflowCommand toCommand() {
        return new CreateWorkflowCommand(
                contextId,
                goal,
                nodes.stream().map(NodeRequest::toSpec).toList()
        );
    }

    public CreateWorkflowCommand toCommand(String tenantId) {
        CreateWorkflowCommand command = toCommand();
        return new CreateWorkflowCommand(tenantId, command.contextId(), command.goal(), command.nodes());
    }

    public record NodeRequest(
            @NotBlank String nodeId,
            @NotBlank String skill,
            @NotBlank String instruction,
            List<String> dependsOn,
            Map<String, String> metadata
    ) {
        TaskNodeSpec toSpec() {
            return new TaskNodeSpec(nodeId, skill, instruction, dependsOn, metadata);
        }
    }
}
