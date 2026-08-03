package io.agentlink.infrastructure;

import io.agentlink.domain.AgentCard;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskState;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentLinkTelemetry {
    private final Tracer tracer;
    private final Map<String, Span> workflowSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> taskSpans = new ConcurrentHashMap<>();

    public AgentLinkTelemetry(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("io.agentlink.orchestration", "0.1.0");
    }

    public void startWorkflow(String contextId, String goal, int taskCount) {
        startWorkflow("_default", contextId, goal, taskCount);
    }

    public void startWorkflow(String tenantId, String contextId, String goal, int taskCount) {
        Span span = tracer.spanBuilder("agentlink.workflow")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("agentlink.context.id", contextId)
                .setAttribute("agentlink.tenant.id", tenantId)
                .setAttribute("agentlink.workflow.goal", goal)
                .setAttribute("agentlink.workflow.task_count", taskCount)
                .startSpan();
        Span previous = workflowSpans.putIfAbsent(contextId, span);
        if (previous != null) {
            span.end();
        }
    }

    public Scope startTask(String contextId, String taskId, String nodeId, String skill, AgentCard agent) {
        Span parent = workflowSpans.get(contextId);
        var builder = tracer.spanBuilder("agentlink.task " + skill)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("agentlink.context.id", contextId)
                .setAttribute("agentlink.task.id", taskId)
                .setAttribute("agentlink.task.node_id", nodeId)
                .setAttribute("agentlink.skill", skill)
                .setAttribute("agentlink.agent.id", agent.id())
                .setAttribute("agentlink.agent.endpoint", agent.endpoint());
        if (parent != null) {
            builder.setParent(Context.root().with(parent));
        }
        Span span = builder.startSpan();
        Span previous = taskSpans.put(taskId, span);
        if (previous != null) {
            previous.end();
        }
        return span.makeCurrent();
    }

    public void runtimeEvent(String taskId, RuntimeEvent event) {
        Span span = taskSpans.get(taskId);
        if (span != null) {
            span.addEvent("runtime." + event.type(), io.opentelemetry.api.common.Attributes.builder()
                    .put("agentlink.runtime", event.runtime())
                    .put("agentlink.runtime.message", event.message())
                    .build());
        }
    }

    public void endTask(String taskId, TaskState state, Throwable failure) {
        Span span = taskSpans.remove(taskId);
        if (span == null) {
            return;
        }
        span.setAttribute("agentlink.task.state", state.name());
        if (failure != null) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getMessage() == null ? state.name() : failure.getMessage());
        } else if (state == TaskState.FAILED || state == TaskState.REJECTED) {
            span.setStatus(StatusCode.ERROR, state.name());
        } else {
            span.setStatus(StatusCode.OK);
        }
        span.end();
    }

    public void leaseLost(String contextId, long fencingToken) {
        Span span = workflowSpans.get(contextId);
        if (span != null) {
            span.addEvent("workflow.lease_lost", io.opentelemetry.api.common.Attributes.of(
                    AttributeKey.longKey("agentlink.workflow.fencing_token"), fencingToken
            ));
            span.setStatus(StatusCode.ERROR, "execution lease lost");
        }
    }

    public void endWorkflow(String contextId, TaskState outcome) {
        Span span = workflowSpans.remove(contextId);
        if (span == null) {
            return;
        }
        span.setAttribute("agentlink.workflow.outcome", outcome.name());
        if (outcome == TaskState.FAILED || outcome == TaskState.REJECTED) {
            span.setStatus(StatusCode.ERROR, outcome.name());
        } else {
            span.setStatus(StatusCode.OK);
        }
        span.end();
    }
}
