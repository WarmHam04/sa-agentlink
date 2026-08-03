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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcPersistenceIntegrationTest {
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void createDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""
        );
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__agentlink_persistence.sql"),
                new ClassPathResource("db/migration/V2__cluster_execution_model.sql"),
                new ClassPathResource("db/migration/V3__cluster_agent_registry.sql"),
                new ClassPathResource("db/migration/V4__agent_streaming_capability.sql"),
                new ClassPathResource("db/migration/V5__outbox_claims.sql")
        )
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
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM agentlink_task WHERE task_id = ?", String.class, task.id()
        )).isEqualTo("COMPLETED");
        assertThat(restored.version()).isEqualTo(2L);
        jdbcTemplate.update("DELETE FROM agentlink_task WHERE task_id = ?", task.id());
        assertThat(store.findByTaskId(task.id())).isEmpty();
        assertThat(store.backfillTaskIndex()).isEqualTo(1);
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
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(attempt_count) FROM agentlink_outbox", Integer.class
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

        new WorkflowRecoveryService(
                store, eventBus, new JdbcWorkflowLeaseManager(jdbcTemplate),
                true, "recovery-test", Duration.ofSeconds(30)
        ).run(null);

        Workflow recovered = store.find("restart-context").orElseThrow();
        assertThat(recovered.task("work").state()).isEqualTo(TaskState.FAILED);
        assertThat(recovered.task("work").error()).contains("service restart");
        assertThat(eventBus.replay("restart-context", 0))
                .extracting(TaskEvent::state)
                .containsExactly(TaskState.FAILED);
    }

    @Test
    void rejectsStaleWorkflowVersionsAndFencesExpiredLeaseOwners() throws Exception {
        JdbcWorkflowStore store = new JdbcWorkflowStore(jdbcTemplate, objectMapper);
        Workflow workflow = new Workflow(
                "fenced-context", "coordinate once",
                List.of(new TaskNodeSpec("work", "analysis", "work", List.of(), Map.of()))
        );
        store.saveIfAbsent(workflow);

        Workflow firstCopy = store.find(workflow.contextId()).orElseThrow();
        Workflow staleCopy = store.find(workflow.contextId()).orElseThrow();
        firstCopy.task("work").transition(TaskState.WORKING, null);
        store.save(firstCopy);
        staleCopy.task("work").transition(TaskState.WORKING, null);
        assertThatThrownBy(() -> store.save(staleCopy))
                .isInstanceOf(io.agentlink.application.WorkflowOptimisticLockException.class);

        JdbcWorkflowLeaseManager leases = new JdbcWorkflowLeaseManager(jdbcTemplate);
        var firstLease = leases.acquire(workflow.contextId(), "instance-a", Duration.ofSeconds(30)).orElseThrow();
        assertThat(leases.acquire(workflow.contextId(), "instance-b", Duration.ofSeconds(1))).isEmpty();
        jdbcTemplate.update(
                "UPDATE agentlink_workflow_lease SET lease_until = ? WHERE context_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), workflow.contextId()
        );
        var secondLease = leases.acquire(workflow.contextId(), "instance-b", Duration.ofSeconds(1)).orElseThrow();
        assertThat(secondLease.fencingToken()).isGreaterThan(firstLease.fencingToken());

        Workflow fencedCopy = store.find(workflow.contextId()).orElseThrow();
        assertThatThrownBy(() -> store.save(fencedCopy, firstLease))
                .isInstanceOf(io.agentlink.application.WorkflowLeaseLostException.class);
        store.save(fencedCopy, secondLease);
        assertThat(fencedCopy.version()).isEqualTo(2L);
    }

    @Test
    void sharesRemoteAgentsButKeepsProcessAgentsNodeLocal() {
        JdbcAgentDirectory firstNode = new JdbcAgentDirectory(jdbcTemplate, "instance-a", Duration.ofSeconds(30));
        JdbcAgentDirectory secondNode = new JdbcAgentDirectory(jdbcTemplate, "instance-b", Duration.ofSeconds(30));
        firstNode.register(new io.agentlink.domain.AgentCard(
                "codex-cli@instance-a", "Codex", "process://codex", "cli-jsonl-v1",
                List.of(new io.agentlink.domain.AgentSkill("coding", "coding")), true, 10
        ));
        firstNode.register(new io.agentlink.domain.AgentCard(
                "agy-cli@instance-a", "Agy", "process://agy", "cli-jsonl-v1",
                List.of(new io.agentlink.domain.AgentSkill("analysis", "analysis")), true, 35
        ));
        firstNode.register(new io.agentlink.domain.AgentCard(
                "analysis-agent@instance-a", "Analysis", "local://analysis", "1.0",
                List.of(new io.agentlink.domain.AgentSkill("analysis", "analysis")), true, 20
        ));
        firstNode.register(new io.agentlink.domain.AgentCard(
                "remote-reviewer", "Reviewer", "https://agents.example.test/a2a", "1.0",
                List.of(new io.agentlink.domain.AgentSkill("code-review", "review")), true, 20
        ));

        assertThat(firstNode.select("coding")).map(io.agentlink.domain.AgentCard::id)
                .contains("codex-cli@instance-a");
        assertThat(firstNode.select("analysis")).map(io.agentlink.domain.AgentCard::id)
                .contains("analysis-agent@instance-a");
        assertThat(secondNode.select("coding")).isEmpty();
        assertThat(secondNode.select("code-review")).map(io.agentlink.domain.AgentCard::id)
                .contains("remote-reviewer");
        assertThat(secondNode.heartbeat("remote-reviewer", true, 5, Duration.ofSeconds(30))).isFalse();
        assertThat(firstNode.heartbeat("remote-reviewer", true, 5, Duration.ofSeconds(30))).isTrue();
        assertThatThrownBy(() -> secondNode.register(new io.agentlink.domain.AgentCard(
                "remote-reviewer", "Hijack", "https://other.example.test/a2a", "1.0",
                List.of(new io.agentlink.domain.AgentSkill("code-review", "review")), true, 1
        ))).isInstanceOf(io.agentlink.application.AgentRegistrationConflictException.class)
                .hasMessageContaining("another live instance");
        assertThat(firstNode.findById("remote-reviewer")).map(io.agentlink.domain.AgentCard::name)
                .contains("Reviewer");
        jdbcTemplate.update(
                "UPDATE agentlink_agent_registry SET lease_until = ? WHERE agent_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), "remote-reviewer"
        );
        secondNode.register(new io.agentlink.domain.AgentCard(
                "remote-reviewer", "Replacement", "https://other.example.test/a2a", "1.0",
                List.of(new io.agentlink.domain.AgentSkill("code-review", "review")), true, 1
        ));
        assertThat(secondNode.findById("remote-reviewer")).map(io.agentlink.domain.AgentCard::name)
                .contains("Replacement");
    }

    private TaskEvent event(String taskId, String message, TaskState state) {
        return new TaskEvent(
                0L, "persistent-context", taskId, "analysis", state, message, null, Instant.now()
        );
    }
}
