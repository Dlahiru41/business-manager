package com.bmp.config;

import com.bmp.shared.security.SystemRole;
import com.bmp.web.security.BmpJwtAuthenticationConverter;
import com.bmp.web.security.ProblemSecurityHandlers;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Actuator security (08-security.md §5): the management port is internal; only {@code health} (probes) and
 * {@code info} are anonymous, everything else (Prometheus, metrics) needs a platform administrator token.
 */
@Configuration(proxyBeanMethods = false)
class ActuatorSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurityFilterChain(
            HttpSecurity http, BmpJwtAuthenticationConverter converter, ProblemSecurityHandlers problemHandlers) {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(EndpointRequest.to("health", "info"))
                        .permitAll()
                        .anyRequest()
                        .hasRole(SystemRole.PLATFORM_ADMIN.code()))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers));
        return http.build();
    }
}
