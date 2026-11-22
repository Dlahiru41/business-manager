package com.bmp.web.config;

import com.bmp.web.problem.ProblemWriter;
import com.bmp.web.security.AccessPolicy;
import com.bmp.web.security.BmpJwtAuthenticationConverter;
import com.bmp.web.security.BmpJwtValidators;
import com.bmp.web.security.BmpPermissionEvaluator;
import com.bmp.web.security.DeviceStatusProvider;
import com.bmp.web.security.PermissionResolver;
import com.bmp.web.security.ProblemSecurityHandlers;
import com.bmp.web.security.SecurityProperties;
import com.bmp.web.security.TenantContextFilter;
import com.bmp.web.security.TenantStatusProvider;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Secure by default (08-security.md): every request needs a valid bearer token (except the error page); controller
 * methods add permission checks with {@code @PreAuthorize("hasPermission(null, '...')")}. Stateless, no CSRF (no
 * cookies), strict CORS allow-list, security headers, Problem Details for 401/403.
 */
@AutoConfiguration(after = WebSupportAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityAutoConfiguration {

    /** Order of the API filter chain; more specific chains (actuator) use a lower number. */
    public static final int API_CHAIN_ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    private static final Duration CORS_MAX_AGE = Duration.ofHours(1);

    @Bean
    @Order(API_CHAIN_ORDER)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            BmpJwtAuthenticationConverter converter,
            ProblemSecurityHandlers problemHandlers,
            TenantContextFilter tenantContextFilter) {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> {})
                .headers(headers -> headers.contentSecurityPolicy(
                                csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(
                                referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(requests -> requests.requestMatchers("/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers))
                .addFilterAfter(tenantContextFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(properties.effectiveJwkSetUri()).build();
        decoder.setJwtValidator(BmpJwtValidators.create(properties.issuerUri(), properties.audience()));
        return decoder;
    }

    @Bean
    BmpJwtAuthenticationConverter bmpJwtAuthenticationConverter(SecurityProperties properties) {
        return new BmpJwtAuthenticationConverter(properties.platformAdminClients());
    }

    @Bean
    ProblemSecurityHandlers problemSecurityHandlers(ProblemWriter problemWriter) {
        return new ProblemSecurityHandlers(problemWriter);
    }

    @Bean
    TenantContextFilter tenantContextFilter(
            TenantStatusProvider tenantStatusProvider,
            DeviceStatusProvider deviceStatusProvider,
            ProblemWriter problemWriter) {
        return new TenantContextFilter(tenantStatusProvider, deviceStatusProvider, problemWriter);
    }

    /** The filter runs inside the security chain only, not as a second servlet filter. */
    @Bean
    FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(TenantContextFilter filter) {
        FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Fail closed until the tenant module provides the real lookup. */
    @Bean
    @ConditionalOnMissingBean
    TenantStatusProvider tenantStatusProvider() {
        return tenant -> TenantStatusProvider.TenantStatus.UNKNOWN;
    }

    /** Fail closed until the iam module provides the real lookup. */
    @Bean
    @ConditionalOnMissingBean
    DeviceStatusProvider deviceStatusProvider() {
        return (tenant, device) -> false;
    }

    @Bean
    @ConditionalOnMissingBean
    PermissionResolver permissionResolver() {
        return PermissionResolver.systemDefaults();
    }

    @Bean
    AccessPolicy accessPolicy(PermissionResolver permissionResolver) {
        return new AccessPolicy(permissionResolver);
    }

    /** Static and lazy: method security infrastructure is created before application beans. */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(ObjectProvider<AccessPolicy> accessPolicy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(new BmpPermissionEvaluator(accessPolicy::getObject));
        return handler;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(WebProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept-Language",
                "Idempotency-Key",
                "If-Match",
                "X-Request-Id",
                TenantContextFilter.TENANT_HEADER));
        cors.setExposedHeaders(
                List.of("ETag", "Location", "X-Request-Id", "Idempotent-Replayed", "Retry-After", "WWW-Authenticate"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(CORS_MAX_AGE);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
