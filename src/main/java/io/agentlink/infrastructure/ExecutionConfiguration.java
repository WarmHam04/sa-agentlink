package io.agentlink.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ExecutionConfiguration {
    @Bean(destroyMethod = "shutdown", name = "agentExecutionExecutor")
    public Executor agentExecutionExecutor(@Value("${agentlink.execution.pool-size:8}") int poolSize) {
        return Executors.newFixedThreadPool(poolSize);
    }

    @Bean(destroyMethod = "shutdown", name = "agentProcessExecutor")
    public Executor agentProcessExecutor() {
        return Executors.newCachedThreadPool();
    }
}
