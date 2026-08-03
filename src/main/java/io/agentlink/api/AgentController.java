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
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentDirectory directory;
    private final A2aDiscoveryService discoveryService;
    private final Duration registrationTtl;
    private final TenantIdentityResolver tenants;

    public AgentController(
            AgentDirectory directory,
            A2aDiscoveryService discoveryService,
            @Value("${agentlink.cluster.agent-registration-ttl:PT45S}") Duration registrationTtl,
            TenantIdentityResolver tenants
    ) {
        this.directory = directory;
        this.discoveryService = discoveryService;
        this.registrationTtl = registrationTtl;
        this.tenants = tenants;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentCard register(
            @Valid @RequestBody RegisterAgentRequest request,
            HttpServletRequest httpRequest
    ) {
        AgentCard card = request.toCard();
        directory.register(tenants.resolve(httpRequest).tenantId(), card);
        return card;
    }

    @GetMapping
    public List<AgentCard> list(HttpServletRequest request) {
        return directory.list(tenants.resolve(request).tenantId());
    }

    @PostMapping("/discover")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentCard discover(
            @Valid @RequestBody DiscoverAgentRequest request,
            HttpServletRequest httpRequest
    ) {
        return discoveryService.discover(
                tenants.resolve(httpRequest).tenantId(), request.cardUrl(), request.id()
        );
    }

    @PostMapping("/{agentId}/heartbeat")
    public void heartbeat(
            @PathVariable String agentId,
            @Valid @RequestBody AgentHeartbeatRequest request,
            HttpServletRequest httpRequest
    ) {
        if (!directory.heartbeat(
                tenants.resolve(httpRequest).tenantId(), agentId,
                request.healthy(), request.load(), registrationTtl
        )) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent registration not found or not owned");
        }
    }

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@PathVariable String agentId, HttpServletRequest request) {
        if (!directory.unregister(tenants.resolve(request).tenantId(), agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent registration not found or not owned");
        }
    }
}
