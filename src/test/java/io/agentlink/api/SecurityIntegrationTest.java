package io.agentlink.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        mockMvc.perform(get("/api/v1/workflows/missing").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_agentlink.read")
                )))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/agents").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_agentlink.read")
                )))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/agents").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_agentlink.agents.read")
                )))
                .andExpect(status().isOk());
    }
}
