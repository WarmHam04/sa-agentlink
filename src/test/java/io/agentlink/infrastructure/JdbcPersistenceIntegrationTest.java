package io.agentlink.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agentlink.domain.Artifact;
import io.agentlink.domain.TaskEvent;
import io.agentlink.domain.TaskNodeSpec;
import io.agentlink.domain.TaskState;
import io.agentlink.domain.Workflow;
import io.agentlink.application.WorkflowRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPersistenceIntegrationTest {
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void createDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__agentlink_persistence.sql"))
                .execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    @Test
    void restoresWorkflowSnapshotAndPersistsOrderedEventsAndOutbox() {
        JdbcWorkflowStore store = new JdbcWorkflowStore(jdbcTemplate, objectMapper);
        JdbcTaskEventBus eventBus = new JdbcTaskEventBus(jdbcTemplate, objectMapper);
        Workflow workflow = new Workflow(
                "persistent-context", "build a report",
                List.of(new TaskNodeSpec("analysis", "analysis", "analyze", List.of(), Map.of()))
        );
        store.saveIfAbsent(workflow);
        var task = workflow.task("analysis");
        task.assign("analysis-agent");
        task.transition(TaskState.WORKING, null);
        store.save(workflow);
        task.complete(new Artifact(
                "artifact-1", task.id(), "analysis", "text/plain", "evidence", Map.of(), Instant.now()
        ));
        store.save(workflow);

        Workflow restored = new JdbcWorkflowStore(jdbcTemplate, objectMapper)
                .find("persistent-context").orElseThrow();
        assertThat(restored.task("analysis").id()).isEqualTo(task.id());
        assertThat(restored.task("analysis").state()).isEqualTo(TaskState.COMPLETED);
        assertThat(restored.task("analysis").artifact().content()).isEqualTo("evidence");
        assertThat(store.findByTaskId(task.id())).isPresent();

        TaskEvent first = eventBus.publish(event(task.id(), "submitted", TaskState.SUBMITTED));
        TaskEvent second = eventBus.publish(event(task.id(), "completed", TaskState.COMPLETED));
        assertThat(second.offset()).isGreaterThan(first.offset());
        assertThat(eventBus.replay("persistent-context", first.offset()))
                .extracting(TaskEvent::message)
                .containsExactly("completed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agentlink_outbox", Integer.class
        )).isEqualTo(5);

        var delivered = new ArrayList<Long>();
        new JdbcOutboxRelay(jdbcTemplate, List.of(message -> delivered.add(message.id()))).relay();
        assertThat(delivered).hasSize(5).doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agentlink_outbox WHERE published_at IS NOT NULL", Integer.class
        )).isEqualTo(5);
    }

    @Test
    void marksInterruptedTasksFailedDuringRestartRecovery() {
        JdbcWorkflowStore store = new JdbcWorkflowStore(jdbcTemplate, objectMapper);
        JdbcTaskEventBus eventBus = new JdbcTaskEventBus(jdbcTemplate, objectMapper);
        Workflow workflow = new Workflow(
                "restart-context", "long running task",
                List.of(new TaskNodeSpec("work", "coding", "work", List.of(), Map.of()))
        );
        store.saveIfAbsent(workflow);
        workflow.task("work").transition(TaskState.WORKING, null);
        store.save(workflow);

        new WorkflowRecoveryService(store, eventBus, true).run(null);

        Workflow recovered = store.find("restart-context").orElseThrow();
        assertThat(recovered.task("work").state()).isEqualTo(TaskState.FAILED);
        assertThat(recovered.task("work").error()).contains("service restart");
        assertThat(eventBus.replay("restart-context", 0))
                .extracting(TaskEvent::state)
                .containsExactly(TaskState.FAILED);
    }

    private TaskEvent event(String taskId, String message, TaskState state) {
        return new TaskEvent(
                0L, "persistent-context", taskId, "analysis", state, message, null, Instant.now()
        );
    }
}
