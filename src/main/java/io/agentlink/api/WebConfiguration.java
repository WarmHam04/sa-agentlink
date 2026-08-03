package io.agentlink.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final TenantAdmissionInterceptor admission;

    public WebConfiguration(TenantAdmissionInterceptor admission) {
        this.admission = admission;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(admission).addPathPatterns("/api/v1/**", "/a2a/**");
    }
}
