package io.agentlink.infrastructure;

import io.agentlink.application.AgentDirectory;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

@Component
public class DemoAgentBootstrap implements ApplicationRunner {
    private final AgentDirectory directory;
    private final boolean enabled;
    private final String localIdSuffix;

    public DemoAgentBootstrap(
            AgentDirectory directory,
            @Value("${agentlink.runtimes.demo-agents:true}") boolean enabled,
            @Value("${agentlink.persistence.mode:memory}") String persistenceMode,
            @Value("${agentlink.cluster.instance-id:${random.uuid}}") String instanceId
    ) {
        this.directory = directory;
        this.enabled = enabled;
        String normalized = instanceId.replaceAll("[^A-Za-z0-9_-]", "");
        String shortId = normalized.isEmpty() ? "node" : normalized.substring(0, Math.min(8, normalized.length()));
        this.localIdSuffix = "jdbc".equalsIgnoreCase(persistenceMode) ? "@" + shortId : "";
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        directory.register(card("knowledge-agent", "Knowledge Agent", "knowledge-search", 15));
        directory.register(card("analysis-agent", "Analysis Agent", "analysis", 20));
        directory.register(card("writer-agent", "Writer Agent", "report-generation", 10));
    }

    private AgentCard card(String id, String name, String skill, int load) {
        return new AgentCard(
                id + localIdSuffix, name, "local://" + id, "1.0",
                List.of(new AgentSkill(skill, "Demo " + skill + " capability")), true, load
        );
    }
}
