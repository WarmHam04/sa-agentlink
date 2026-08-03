package io.agentlink.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
@ConditionalOnProperty(name = "agentlink.persistence.mode", havingValue = "jdbc")
public class TaskIndexBackfill implements ApplicationRunner {
    private final JdbcWorkflowStore store;

    public TaskIndexBackfill(JdbcWorkflowStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        store.backfillTaskIndex();
    }
}
