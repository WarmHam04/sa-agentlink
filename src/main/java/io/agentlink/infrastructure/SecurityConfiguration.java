package io.agentlink.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;

@Configuration
public class SecurityConfiguration {
    @Bean
    public SecurityFilterChain agentLinkSecurity(
            HttpSecurity http,
            @Value("${agentlink.security.mode:permit-all}") String mode
    ) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!"oauth2".equalsIgnoreCase(mode)) {
            return http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
        }

        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/.well-known/agent-card.json", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_agentlink.metrics")
                        .requestMatchers(HttpMethod.GET, "/api/v1/agents/**")
                        .hasAuthority("SCOPE_agentlink.agents.read")
                        .requestMatchers("/api/v1/agents/**").hasAuthority("SCOPE_agentlink.agents.write")
                        .requestMatchers("/api/v1/workflows/*:cancel", "/a2a/tasks/*:cancel")
                        .hasAuthority("SCOPE_agentlink.cancel")
                        .requestMatchers("/api/v1/workflows", "/a2a/message:send", "/a2a/message:stream")
                        .hasAuthority("SCOPE_agentlink.execute")
                        .requestMatchers("/api/v1/workflows/**", "/api/v1/contexts/**", "/a2a/tasks/**")
                        .hasAuthority("SCOPE_agentlink.read")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .build();
    }
}
