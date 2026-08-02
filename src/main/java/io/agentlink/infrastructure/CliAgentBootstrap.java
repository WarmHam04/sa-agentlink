package io.agentlink.infrastructure;

import io.agentlink.application.AgentDirectory;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Component
public class CliAgentBootstrap implements ApplicationRunner {
    private final AgentDirectory directory;
    private final boolean enabled;

    public CliAgentBootstrap(
            AgentDirectory directory,
            @Value("${agentlink.runtimes.bootstrap-cli:true}") boolean enabled
    ) {
        this.directory = directory;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        registerIfAvailable("codex", "OpenAI Codex CLI", 20,
                skill("coding", "Repository-aware coding and code modification"),
                skill("code-review", "Code review and risk analysis"));
        registerIfAvailable("gemini", "Google Gemini CLI", 30,
                skill("coding", "Repository-aware coding and analysis"),
                skill("analysis", "General and multimodal analysis"));
        registerIfAvailable("claude", "Anthropic Claude Code", 25,
                skill("coding", "Repository-aware coding and code modification"),
                skill("code-review", "Code review and architecture analysis"));
        registerIfAvailable("agy", "Google Antigravity CLI", 35,
                skill("coding", "Antigravity coding agent"),
                skill("analysis", "Project analysis and planning"));
    }

    private void registerIfAvailable(String executable, String name, int load, AgentSkill... skills) {
        if (findOnPath(executable)) {
            directory.register(new AgentCard(
                    executable + "-cli", name, "process://" + executable, "cli-jsonl-v1",
                    List.of(skills), true, load
            ));
        }
    }

    private boolean findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        return Arrays.stream(path.split(File.pathSeparator))
                .map(directory -> Path.of(directory, executable))
                .anyMatch(candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate));
    }

    private AgentSkill skill(String name, String description) {
        return new AgentSkill(name, description);
    }
}
