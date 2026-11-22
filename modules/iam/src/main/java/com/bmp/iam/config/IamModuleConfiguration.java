package com.bmp.iam.config;

import com.bmp.iam.adapter.out.idp.KeycloakIdentityProvider;
import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.iam.domain.port.IdentityProviderGateway;
import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Registers the iam module with the platform and wires the Keycloak gateway with its resilience policies. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IamProperties.class)
class IamModuleConfiguration {

    /** Migrates after tenant (10), the only module iam depends on. */
    static final int MIGRATION_ORDER = 20;

    static final String CIRCUIT_BREAKER = "keycloak";

    private static final int SLIDING_WINDOW = 20;
    private static final int MINIMUM_CALLS = 10;
    private static final float FAILURE_RATE_PERCENT = 50;
    private static final Duration OPEN_STATE = Duration.ofSeconds(30);
    private static final int HALF_OPEN_CALLS = 3;
    private static final long MAX_RETRIES = 2;
    private static final Duration RETRY_DELAY = Duration.ofMillis(200);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(1);

    @Bean
    ModuleMigration iamMigration() {
        return new ModuleMigration("iam", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages iamMessages() {
        return new ModuleMessages("i18n/iam/messages");
    }

    @Bean
    ConstraintErrorMapping roleCodeTaken() {
        return ConstraintErrorMapping.of("uk_role_tenant_code", IamErrorCode.ROLE_CODE_TAKEN);
    }

    @Bean
    IdentityProviderGateway keycloakIdentityProvider(
            IamProperties properties,
            ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<MeterRegistry> meters,
            Clock clock) {
        IamProperties.Keycloak keycloak = properties.keycloak();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(keycloak.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requestFactory.setReadTimeout(keycloak.readTimeout());
        RestClient http = RestClient.builder()
                .baseUrl(keycloak.baseUrl().toString())
                .requestFactory(requestFactory)
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
        return new KeycloakIdentityProvider(
                http,
                new KeycloakIdentityProvider.Settings(
                        keycloak.baseUrl(),
                        keycloak.realm(),
                        keycloak.clientId(),
                        keycloak.clientSecret(),
                        keycloak.apiAudience(),
                        keycloak.inviteLifespan()),
                circuitBreaker(meters),
                retryTemplate(),
                new SecureRandom(),
                clock);
    }

    /** Opens after half of the last 20 calls failed (I/O errors and 5xx only) and probes again after 30 s. */
    static CircuitBreaker circuitBreaker(ObjectProvider<MeterRegistry> meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(FAILURE_RATE_PERCENT)
                .waitDurationInOpenState(OPEN_STATE)
                .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_CALLS)
                .recordException(e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException)
                .build());
        meters.ifAvailable(registryOfMeters ->
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(registryOfMeters));
        return registry.circuitBreaker(CIRCUIT_BREAKER);
    }

    /** Two retries with exponential back-off, for I/O errors and 5xx of idempotent calls only. */
    static RetryTemplate retryTemplate() {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(MAX_RETRIES)
                .delay(RETRY_DELAY)
                .multiplier(2)
                .maxDelay(MAX_RETRY_DELAY)
                .includes(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
    }
}
