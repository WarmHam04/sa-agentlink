package io.agentlink.api;

import io.agentlink.application.TenantAdministration;
import io.agentlink.application.TenantPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class TenantAdminController {
    private final TenantAdministration administration;
    private final TenantIdentityResolver tenants;

    public TenantAdminController(TenantAdministration administration, TenantIdentityResolver tenants) {
        this.administration = administration;
        this.tenants = tenants;
    }

    @GetMapping("/tenant-policy")
    public TenantPolicy policy(HttpServletRequest request) {
        return administration.policy(tenants.resolve(request).tenantId());
    }

    @PutMapping("/tenant-policy")
    public TenantPolicy update(
            @Valid @RequestBody TenantPolicyUpdateRequest update,
            HttpServletRequest request
    ) {
        return administration.updateLimits(
                tenants.resolve(request).tenantId(), update.enabled(), update.requestsPerMinute(),
                update.workflowsPerDay(), update.maxActiveWorkflows()
        );
    }

    @PostMapping("/tenant-policy:failover")
    public TenantPolicy failover(
            @Valid @RequestBody TenantFailoverRequest failover,
            HttpServletRequest request
    ) {
        return administration.failover(
                tenants.resolve(request).tenantId(), failover.homeRegion(), failover.expectedRoutingEpoch()
        );
    }
}
