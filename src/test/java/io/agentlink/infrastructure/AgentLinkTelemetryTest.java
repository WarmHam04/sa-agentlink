package io.agentlink.infrastructure;

import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import io.agentlink.domain.RuntimeEvent;
import io.agentlink.domain.TaskState;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLinkTelemetryTest {
    @Test
    void linksTaskSpansToWorkflowAndRecordsRuntimeEvents() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        AgentLinkTelemetry telemetry = new AgentLinkTelemetry(sdk);
        AgentCard agent = new AgentCard(
                "codex", "Codex", "process://codex", "cli-jsonl-v1",
                List.of(new AgentSkill("coding", "coding")), true, 1
        );

        telemetry.startWorkflow("context-1", "implement", 1);
        try (var ignored = telemetry.startTask("context-1", "task-1", "code", "coding", agent)) {
            telemetry.runtimeEvent("task-1", RuntimeEvent.of("codex", "turn.started", "started"));
        }
        telemetry.endTask("task-1", TaskState.COMPLETED, null);
        telemetry.endWorkflow("context-1", TaskState.COMPLETED);

        var spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        var workflow = spans.stream().filter(span -> span.getName().equals("agentlink.workflow"))
                .findFirst().orElseThrow();
        var task = spans.stream().filter(span -> span.getName().equals("agentlink.task coding"))
                .findFirst().orElseThrow();
        assertThat(task.getParentSpanContext().getSpanId()).isEqualTo(workflow.getSpanId());
        assertThat(task.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(task.getEvents()).extracting(event -> event.getName())
                .contains("runtime.turn.started");
        provider.close();
    }
}
