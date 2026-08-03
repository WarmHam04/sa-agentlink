package io.agentlink.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
        "agentlink.security.mode=oauth2",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.test/.well-known/jwks.json"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void keepsDiscoveryPublicAndRequiresScopedBearerTokens() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/workflows/missing"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/workflows/missing").with(jwt()
                        .jwt(token -> token.claim("tenant_id", "tenant-a"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.read"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/agents").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_agentlink.read")
                )))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/agents").with(jwt()
                        .jwt(token -> token.claim("tenant_id", "tenant-a"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.agents.read"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/tenant-policy").with(jwt()
                        .jwt(token -> token.claim("tenant_id", "tenant-a"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.read"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/tenant-policy").with(jwt()
                        .jwt(token -> token.claim("tenant_id", "tenant-a"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.admin"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/tenant-policy:failover")
                        .with(jwt().jwt(token -> token.claim("tenant_id", "tenant-admin-smoke"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"homeRegion\":\"region-b\",\"expectedRoutingEpoch\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeRegion").value("region-b"))
                .andExpect(jsonPath("$.routingEpoch").value(2));
    }

    @Test
    void isolatesWorkflowResourcesByJwtTenantAndAllowsExplicitAdminDelegation() throws Exception {
        var tenantAExecute = jwt().jwt(token -> token.claim("tenant_id", "tenant-a"))
                .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.execute"));
        mockMvc.perform(post("/api/v1/workflows")
                        .with(tenantAExecute)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contextId":"security-tenant-context",
                                  "goal":"tenant isolation",
                                  "nodes":[{
                                    "nodeId":"analysis",
                                    "skill":"analysis",
                                    "instruction":"analyze",
                                    "dependsOn":[]
                                  }]
                                }
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/workflows/security-tenant-context").with(jwt()
                        .jwt(token -> token.claim("tenant_id", "tenant-a"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.read"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/workflows/security-tenant-context").with(jwt()
                        .jwt(token -> token.claim("tenant_id", "tenant-b"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.read"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/workflows/security-tenant-context")
                        .header(TenantIdentityResolver.TENANT_HEADER, "tenant-a")
                        .with(jwt().jwt(token -> token.claim("tenant_id", "tenant-b"))
                                .authorities(
                                        new SimpleGrantedAuthority("SCOPE_agentlink.admin"),
                                        new SimpleGrantedAuthority("SCOPE_agentlink.read")
                                )))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/workflows/security-tenant-context").with(jwt()
                        .authorities(new SimpleGrantedAuthority("SCOPE_agentlink.read"))))
                .andExpect(status().isForbidden());
    }
}
