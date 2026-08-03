package io.agentlink.api;

import io.agentlink.application.AgentDirectory;
import io.agentlink.application.A2aDiscoveryService;
import io.agentlink.domain.AgentCard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentDirectory directory;
    private final A2aDiscoveryService discoveryService;
    private final Duration registrationTtl;

    public AgentController(
            AgentDirectory directory,
            A2aDiscoveryService discoveryService,
            @Value("${agentlink.cluster.agent-registration-ttl:PT45S}") Duration registrationTtl
    ) {
        this.directory = directory;
        this.discoveryService = discoveryService;
        this.registrationTtl = registrationTtl;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentCard register(@Valid @RequestBody RegisterAgentRequest request) {
        AgentCard card = request.toCard();
        directory.register(card);
        return card;
    }

    @GetMapping
    public List<AgentCard> list() {
        return directory.list();
    }

    @PostMapping("/discover")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentCard discover(@Valid @RequestBody DiscoverAgentRequest request) {
        return discoveryService.discover(request.cardUrl(), request.id());
    }

    @PostMapping("/{agentId}/heartbeat")
    public void heartbeat(
            @PathVariable String agentId,
            @Valid @RequestBody AgentHeartbeatRequest request
    ) {
        if (!directory.heartbeat(agentId, request.healthy(), request.load(), registrationTtl)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent registration not found or not owned");
        }
    }

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@PathVariable String agentId) {
        if (!directory.unregister(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent registration not found or not owned");
        }
    }
}
