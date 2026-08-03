package io.agentlink.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentlink.application.CreateWorkflowCommand;
import io.agentlink.application.TaskView;
import io.agentlink.application.WorkflowOrchestrator;
import io.agentlink.application.WorkflowView;
import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.TaskState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@RestController
public class A2aServerController {
    private final WorkflowOrchestrator orchestrator;
    private final String publicBaseUrl;
    private final Duration blockingTimeout;
    private final Executor streamExecutor;
    private final TenantIdentityResolver tenants;

    public A2aServerController(
            WorkflowOrchestrator orchestrator,
            @Value("${agentlink.a2a.public-base-url:http://localhost:8096}") String publicBaseUrl,
            @Value("${agentlink.a2a.blocking-timeout:PT2M}") Duration blockingTimeout,
            @Qualifier("agentProcessExecutor") Executor streamExecutor,
            TenantIdentityResolver tenants
    ) {
        this.orchestrator = orchestrator;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.blockingTimeout = blockingTimeout;
        this.streamExecutor = streamExecutor;
        this.tenants = tenants;
    }

    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> agentCard() {
        return Map.of(
                "name", "SA-AgentLink Coordinator",
                "description", "Routes and orchestrates tasks across local CLI and remote A2A agents",
                "supportedInterfaces", List.of(Map.of(
                        "url", publicBaseUrl + "/a2a",
                        "protocolBinding", "https://a2a-protocol.org/protocol-bindings/http+json",
                        "protocolVersion", "1.0"
                )),
                "capabilities", Map.of("streaming", true, "pushNotifications", false),
                "defaultInputModes", List.of("text/plain"),
                "defaultOutputModes", List.of("text/plain", "text/markdown"),
                "skills", List.of(Map.of(
                        "id", "orchestration",
                        "name", "Multi-agent orchestration",
                        "description", "Delegate a task to a selected AgentLink skill or runtime"
                ))
        );
    }

    @PostMapping(
            value = "/a2a/message:send",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/a2a+json"},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> sendMessage(@RequestBody JsonNode request, HttpServletRequest httpRequest) {
        String tenantId = tenants.resolve(httpRequest).tenantId();
        WorkflowView workflow = createWorkflow(tenantId, request);
        boolean returnImmediately = request.path("configuration").path("returnImmediately").asBoolean(false);
        WorkflowView response = returnImmediately ? workflow : awaitTerminal(tenantId, workflow.contextId());
        return toA2aTask(response);
    }

    @PostMapping(
            value = "/a2a/message:stream",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/a2a+json"},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamMessage(@RequestBody JsonNode request, HttpServletRequest httpRequest) {
        String tenantId = tenants.resolve(httpRequest).tenantId();
        WorkflowView workflow = createWorkflow(tenantId, request);
        SseEmitter emitter = new SseEmitter(blockingTimeout.toMillis());
        streamExecutor.execute(() -> streamWorkflow(tenantId, workflow.contextId(), emitter));
        return emitter;
    }

    @GetMapping(value = "/a2a/tasks/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTask(@PathVariable String taskId, HttpServletRequest request) {
        return toA2aTask(orchestrator.getByTaskId(tenants.resolve(request).tenantId(), taskId));
    }

    @PostMapping(value = "/a2a/tasks/{taskId}:cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cancelTask(@PathVariable String taskId, HttpServletRequest request) {
        return toA2aTask(orchestrator.cancelByTaskId(tenants.resolve(request).tenantId(), taskId));
    }

    private WorkflowView createWorkflow(String tenantId, JsonNode request) {
        JsonNode message = request.path("message");
        String instruction = extractText(message.path("parts"));
        if (instruction.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A2A message must contain a text Part");
        }

        JsonNode metadataNode = request.path("metadata");
        String skill = metadataNode.path("skill").asText("analysis");
        Map<String, String> metadata = new LinkedHashMap<>();
        copyText(metadataNode, metadata, "agentId");
        copyText(metadataNode, metadata, "workingDirectory");
        copyText(metadataNode, metadata, "sandbox");
        copyText(metadataNode, metadata, "approvalMode");
        copyText(metadataNode, metadata, "permissionMode");
        copyText(metadataNode, metadata, "maxAttempts");

        String requestedContextId = message.path("contextId").asText("");
        String contextId = requestedContextId.isBlank() ? UUID.randomUUID().toString() : requestedContextId;
        return orchestrator.create(new CreateWorkflowCommand(
                tenantId,
                contextId,
                instruction,
                List.of(new TaskNodeSpec("delegate", skill, instruction, List.of(), metadata))
        ));
    }

    private void streamWorkflow(String tenantId, String contextId, SseEmitter emitter) {
        Instant deadline = Instant.now().plus(blockingTimeout);
        Instant lastUpdate = Instant.EPOCH;
        try {
            while (Instant.now().isBefore(deadline)) {
                WorkflowView workflow = orchestrator.get(tenantId, contextId);
                Instant update = workflow.tasks().stream()
                        .map(TaskView::updatedAt).max(Instant::compareTo).orElse(Instant.EPOCH);
                if (update.isAfter(lastUpdate)) {
                    emitter.send(SseEmitter.event()
                            .name("status-update")
                            .data(toA2aTask(workflow), MediaType.APPLICATION_JSON));
                    lastUpdate = update;
                }
                if (workflow.tasks().stream().allMatch(task -> task.state().terminal())) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(25);
            }
            emitter.completeWithError(new IllegalStateException("A2A stream timed out"));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            emitter.completeWithError(failure);
        }
    }

    private WorkflowView awaitTerminal(String tenantId, String contextId) {
        Instant deadline = Instant.now().plus(blockingTimeout);
        WorkflowView workflow;
        do {
            workflow = orchestrator.get(tenantId, contextId);
            if (workflow.tasks().stream().allMatch(task -> task.state().terminal())) {
                return workflow;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "A2A request interrupted");
            }
        } while (Instant.now().isBefore(deadline));
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "A2A task did not finish before blocking timeout");
    }

    private Map<String, Object> toA2aTask(WorkflowView workflow) {
        TaskView task = workflow.tasks().get(0);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", task.taskId());
        response.put("contextId", workflow.contextId());
        response.put("status", Map.of(
                "state", a2aState(task.state()),
                "timestamp", task.updatedAt().toString(),
                "message", Map.of(
                        "messageId", UUID.randomUUID().toString(),
                        "role", "ROLE_AGENT",
                        "parts", List.of(Map.of("text", task.error() == null ? task.state().name() : task.error()))
                )
        ));
        if (task.artifact() != null) {
            response.put("artifacts", List.of(Map.of(
                    "artifactId", task.artifact().id(),
                    "name", task.artifact().name(),
                    "parts", List.of(Map.of("text", task.artifact().content()))
            )));
        } else {
            response.put("artifacts", List.of());
        }
        return response;
    }

    private String a2aState(TaskState state) {
        return switch (state) {
            case SUBMITTED -> "TASK_STATE_SUBMITTED";
            case WORKING -> "TASK_STATE_WORKING";
            case INPUT_REQUIRED -> "TASK_STATE_INPUT_REQUIRED";
            case COMPLETED -> "TASK_STATE_COMPLETED";
            case FAILED -> "TASK_STATE_FAILED";
            case CANCELED -> "TASK_STATE_CANCELED";
            case REJECTED -> "TASK_STATE_REJECTED";
        };
    }

    private String extractText(JsonNode parts) {
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        parts.forEach(part -> {
            if (part.path("text").isTextual()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(part.path("text").asText());
            }
        });
        return text.toString();
    }

    private void copyText(JsonNode source, Map<String, String> destination, String field) {
        String value = source.path(field).asText("");
        if (!value.isBlank()) {
            destination.put(field, value);
        }
    }
}
