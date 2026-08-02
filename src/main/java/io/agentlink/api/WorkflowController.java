package io.agentlink.api;

import io.agentlink.application.TaskEventBus;
import io.agentlink.application.WorkflowOrchestrator;
import io.agentlink.application.WorkflowView;
import jakarta.validation.Valid;
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

    public WorkflowController(WorkflowOrchestrator orchestrator, TaskEventBus eventBus) {
        this.orchestrator = orchestrator;
        this.eventBus = eventBus;
    }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WorkflowView create(@Valid @RequestBody CreateWorkflowRequest request) {
        return orchestrator.create(request.toCommand());
    }

    @GetMapping("/workflows/{contextId}")
    public WorkflowView get(@PathVariable String contextId) {
        return orchestrator.get(contextId);
    }

    @PostMapping("/workflows/{contextId}:cancel")
    public WorkflowView cancel(@PathVariable String contextId) {
        return orchestrator.cancel(contextId);
    }

    @GetMapping(value = "/contexts/{contextId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String contextId,
            @RequestParam(defaultValue = "0") long after
    ) {
        return eventBus.subscribe(contextId, after);
    }
}
