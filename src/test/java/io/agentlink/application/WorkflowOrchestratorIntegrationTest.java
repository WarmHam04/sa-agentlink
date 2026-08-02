package io.agentlink.application;

import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class WorkflowOrchestratorIntegrationTest {
    @Autowired
    private WorkflowOrchestrator orchestrator;

    @Test
    void executesParallelBranchesAndPassesArtifactsToDependentAgent() throws Exception {
        WorkflowView created = orchestrator.create(new CreateWorkflowCommand(
                "integration-context",
                "research and generate a report",
                List.of(
                        new TaskNodeSpec("search", "knowledge-search", "find evidence", List.of()),
                        new TaskNodeSpec("analyze", "analysis", "analyze the question", List.of()),
                        new TaskNodeSpec("write", "report-generation", "write the final report", List.of("search", "analyze"))
                )
        ));

        WorkflowView completed = awaitCompleted(created.contextId());
        assertThat(completed.tasks()).allMatch(task -> task.state() == TaskState.COMPLETED);
        TaskView writer = completed.tasks().stream().filter(task -> task.nodeId().equals("write")).findFirst().orElseThrow();
        assertThat(writer.artifact().content()).contains("find evidence", "analyze the question");
    }

    @Test
    void rejectsCyclicTaskGraph() {
        CreateWorkflowCommand command = new CreateWorkflowCommand(
                null,
                "invalid graph",
                List.of(
                        new TaskNodeSpec("a", "analysis", "a", List.of("b")),
                        new TaskNodeSpec("b", "analysis", "b", List.of("a"))
                )
        );

        assertThatThrownBy(() -> orchestrator.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void sameContextAndDefinitionIsIdempotent() {
        CreateWorkflowCommand command = new CreateWorkflowCommand(
                "idempotent-context",
                "analyze once",
                List.of(new TaskNodeSpec("analysis", "analysis", "analyze", List.of()))
        );

        WorkflowView first = orchestrator.create(command);
        WorkflowView second = orchestrator.create(command);

        assertThat(second.contextId()).isEqualTo(first.contextId());
        assertThat(second.tasks()).extracting(TaskView::taskId)
                .containsExactlyElementsOf(first.tasks().stream().map(TaskView::taskId).toList());
    }

    private WorkflowView awaitCompleted(String contextId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        WorkflowView view;
        do {
            view = orchestrator.get(contextId);
            if (view.tasks().stream().allMatch(task -> task.state().terminal())) {
                return view;
            }
            Thread.sleep(20);
        } while (Instant.now().isBefore(deadline));
        return view;
    }
}
