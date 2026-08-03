package io.agentlink.api;

import io.agentlink.application.TaskEventBus;
import io.agentlink.application.WorkflowOrchestrator;
import io.agentlink.application.WorkflowView;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController {
    private final WorkflowOrchestrator orchestrator;
    private final TaskEventBus eventBus;
    private final TenantIdentityResolver tenants;

    public WorkflowController(
            WorkflowOrchestrator orchestrator,
            TaskEventBus eventBus,
            TenantIdentityResolver tenants
    ) {
        this.orchestrator = orchestrator;
        this.eventBus = eventBus;
        this.tenants = tenants;
    }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WorkflowView create(
            @Valid @RequestBody CreateWorkflowRequest request,
            HttpServletRequest httpRequest
    ) {
        String tenantId = tenants.resolve(httpRequest).tenantId();
        return orchestrator.create(request.toCommand(tenantId));
    }

    @GetMapping("/workflows/{contextId}")
    public WorkflowView get(@PathVariable String contextId, HttpServletRequest request) {
        return orchestrator.get(tenants.resolve(request).tenantId(), contextId);
    }

    @PostMapping("/workflows/{contextId}:cancel")
    public WorkflowView cancel(@PathVariable String contextId, HttpServletRequest request) {
        return orchestrator.cancel(tenants.resolve(request).tenantId(), contextId);
    }

    @GetMapping(value = "/contexts/{contextId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String contextId,
            @RequestParam(defaultValue = "0") long after,
            HttpServletRequest request
    ) {
        String tenantId = tenants.resolve(request).tenantId();
        orchestrator.get(tenantId, contextId);
        return eventBus.subscribe(tenantId, contextId, after);
    }
}
