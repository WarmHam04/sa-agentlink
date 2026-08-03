package io.agentlink.api;

import io.agentlink.application.TenantControlPlane;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantAdmissionInterceptor implements HandlerInterceptor {
    private final TenantIdentityResolver tenants;
    private final TenantControlPlane controlPlane;

    public TenantAdmissionInterceptor(TenantIdentityResolver tenants, TenantControlPlane controlPlane) {
        this.tenants = tenants;
        this.controlPlane = controlPlane;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (request.getRequestURI().startsWith("/api/v1/admin/")) {
            return true;
        }
        TenantIdentity identity = tenants.resolve(request);
        controlPlane.admitRequest(identity.tenantId(), operation(request));
        return true;
    }

    private String operation(HttpServletRequest request) {
        String path = request.getRequestURI();
        String family = path.startsWith("/a2a/") ? "a2a"
                : path.startsWith("/api/v1/agents") ? "agents"
                : path.startsWith("/api/v1/admin") ? "admin"
                : "workflows";
        return request.getMethod().toLowerCase() + '-' + family;
    }
}
