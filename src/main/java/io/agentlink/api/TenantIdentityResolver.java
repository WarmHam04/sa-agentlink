package io.agentlink.api;

import io.agentlink.application.TenantAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class TenantIdentityResolver {
    public static final String TENANT_HEADER = "X-AgentLink-Tenant";
    private static final Pattern VALID_TENANT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,63}");

    private final boolean oauth2;
    private final String claimName;
    private final String defaultTenant;

    public TenantIdentityResolver(
            @Value("${agentlink.security.mode:permit-all}") String securityMode,
            @Value("${agentlink.security.tenant-claim:tenant_id}") String claimName,
            @Value("${agentlink.security.default-tenant:_default}") String defaultTenant
    ) {
        this.oauth2 = "oauth2".equalsIgnoreCase(securityMode);
        this.claimName = claimName;
        this.defaultTenant = validate(defaultTenant);
    }

    public TenantIdentity resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("SCOPE_agentlink.admin"));
        String requestedTenant = request.getHeader(TENANT_HEADER);

        if (!oauth2) {
            return new TenantIdentity(
                    requestedTenant == null || requestedTenant.isBlank() ? defaultTenant : validate(requestedTenant),
                    authentication == null ? "local" : authentication.getName(),
                    true
            );
        }
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new TenantAccessDeniedException("a tenant-scoped JWT is required");
        }
        String tokenTenant = jwt.getToken().getClaimAsString(claimName);
        if (tokenTenant == null || tokenTenant.isBlank()) {
            throw new TenantAccessDeniedException("JWT is missing tenant claim: " + claimName);
        }
        String tenantId = validate(tokenTenant);
        if (requestedTenant != null && !requestedTenant.isBlank()) {
            if (!admin) {
                throw new TenantAccessDeniedException("only agentlink.admin may override the JWT tenant");
            }
            tenantId = validate(requestedTenant);
        }
        return new TenantIdentity(tenantId, jwt.getName(), admin);
    }

    private String validate(String tenantId) {
        if (tenantId == null || !VALID_TENANT.matcher(tenantId).matches()) {
            throw new TenantAccessDeniedException("invalid tenant identifier");
        }
        return tenantId;
    }
}
