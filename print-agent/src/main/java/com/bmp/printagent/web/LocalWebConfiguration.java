package com.bmp.printagent.web;

import com.bmp.printagent.config.AgentProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The shared token on every request, and CORS for the till's origins when {@code agent.allowed-origins} names any. */
@Configuration(proxyBeanMethods = false)
class LocalWebConfiguration implements WebMvcConfigurer {

    private final AgentProperties settings;

    LocalWebConfiguration(AgentProperties settings) {
        this.settings = settings;
    }

    @Bean
    FilterRegistrationBean<LocalTokenFilter> localTokenFilter() {
        FilterRegistrationBean<LocalTokenFilter> registration =
                new FilterRegistrationBean<>(new LocalTokenFilter(settings.localToken()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!settings.allowedOrigins().isEmpty()) {
            registry.addMapping("/v1/**")
                    .allowedOrigins(settings.allowedOrigins().toArray(String[]::new))
                    .allowedMethods("GET", "POST")
                    .allowedHeaders("Authorization", "Content-Type");
        }
    }
}
