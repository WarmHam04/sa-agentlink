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

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentDirectory directory;
    private final A2aDiscoveryService discoveryService;

    public AgentController(AgentDirectory directory, A2aDiscoveryService discoveryService) {
        this.directory = directory;
        this.discoveryService = discoveryService;
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
}
