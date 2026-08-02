package io.agentlink.infrastructure;

import io.agentlink.domain.TaskNodeSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Component
public class CliExecutionPolicy {
    private final Path workspaceRoot;
    private final Duration timeout;
    private final int maxArtifactChars;

    public CliExecutionPolicy(
            @Value("${agentlink.runtimes.workspace-root:${user.dir}}") String workspaceRoot,
            @Value("${agentlink.runtimes.timeout:PT10M}") Duration timeout,
            @Value("${agentlink.runtimes.max-artifact-chars:100000}") int maxArtifactChars
    ) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.timeout = timeout;
        this.maxArtifactChars = maxArtifactChars;
    }

    public Path workingDirectory(TaskNodeSpec task) {
        String configured = task.metadata().getOrDefault("workingDirectory", ".");
        Path candidate = Path.of(configured);
        Path resolved = (candidate.isAbsolute() ? candidate : workspaceRoot.resolve(candidate))
                .toAbsolutePath()
                .normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("workingDirectory escapes configured workspace root");
        }
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("workingDirectory does not exist: " + resolved);
        }
        return resolved;
    }

    public Duration timeout() {
        return timeout;
    }

    public String limit(String value) {
        if (value == null || value.length() <= maxArtifactChars) {
            return value;
        }
        return value.substring(0, maxArtifactChars) + "\n[truncated by AgentLink]";
    }
}
