package io.agentlink.infrastructure;

import io.agentlink.application.AgentDirectory;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DemoAgentBootstrap implements ApplicationRunner {
    private final AgentDirectory directory;

    public DemoAgentBootstrap(AgentDirectory directory) {
        this.directory = directory;
    }

    @Override
    public void run(ApplicationArguments args) {
        directory.register(card("knowledge-agent", "Knowledge Agent", "knowledge-search", 15));
        directory.register(card("analysis-agent", "Analysis Agent", "analysis", 20));
        directory.register(card("writer-agent", "Writer Agent", "report-generation", 10));
    }

    private AgentCard card(String id, String name, String skill, int load) {
        return new AgentCard(
                id, name, "local://" + id, "1.0",
                List.of(new AgentSkill(skill, "Demo " + skill + " capability")), true, load
        );
    }
}
