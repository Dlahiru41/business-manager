package com.bmp.web.config;

import com.bmp.shared.id.IdGenerator;
import com.bmp.web.idempotency.IdempotencyFilter;
import com.bmp.web.idempotency.IdempotencyInterceptor;
import com.bmp.web.idempotency.IdempotencyStore;
import com.bmp.web.idempotency.RequestHasher;
import com.bmp.web.json.BmpJacksonModule;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ConstraintErrorRegistry;
import com.bmp.web.problem.GlobalExceptionHandler;
import com.bmp.web.problem.ModuleMessages;
import com.bmp.web.problem.ProblemFactory;
import com.bmp.web.problem.ProblemMessages;
import com.bmp.web.problem.ProblemWriter;
import com.bmp.web.request.AccessLogFilter;
import com.bmp.web.request.MdcTaskDecorator;
import com.bmp.web.request.ModuleMdcInterceptor;
import com.bmp.web.request.RequestIdFilter;
import com.bmp.web.request.RequestSizeLimitFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the web edge shared by every module: request ids, access log, size limit, Problem Details, i18n,
 * idempotency and JSON conventions. Filters run before Spring Security (order {@code -100}).
 */
@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebProperties.class)
public class WebSupportAutoConfiguration {

    /** Platform bundle with titles of {@code PlatformErrorCode}s. */
    public static final String PLATFORM_MESSAGES = "i18n/platform/messages";

    static final int REQUEST_ID_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    static final int ACCESS_LOG_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    static final int SIZE_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 30;
    static final int IDEMPOTENCY_ORDER = Ordered.HIGHEST_PRECEDENCE + 40;

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    IdGenerator idGenerator() {
        return IdGenerator.uuidV7();
    }

    @Bean(name = "messageSource")
    MessageSource messageSource(List<ModuleMessages> modules) {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        List<String> basenames = new ArrayList<>();
        basenames.add(PLATFORM_MESSAGES);
        modules.forEach(m -> basenames.add(m.basename()));
        source.setBasenames(basenames.toArray(String[]::new));
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Bean(name = "localeResolver")
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.of("si"), Locale.of("ta")));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    @Bean
    ProblemFactory problemFactory(WebProperties properties, MessageSource messageSource, Clock clock) {
        return new ProblemFactory(properties.problemBaseUri(), new ProblemMessages(messageSource), clock);
    }

    @Bean
    ConstraintErrorRegistry constraintErrorRegistry(List<ConstraintErrorMapping> mappings) {
        return new ConstraintErrorRegistry(mappings);
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler(
            ProblemFactory problems, ConstraintErrorRegistry constraints, LocaleResolver localeResolver) {
        return new GlobalExceptionHandler(problems, constraints, localeResolver);
    }

    @Bean
    ProblemWriter problemWriter(ProblemFactory problems, JsonMapper jsonMapper, LocaleResolver localeResolver) {
        return new ProblemWriter(problems, jsonMapper, localeResolver);
    }

    @Bean
    BmpJacksonModule bmpJacksonModule() {
        return new BmpJacksonModule();
    }

    /** Requests reject unknown properties to catch typos (03-api-standards.md §3). */
    @Bean
    JsonMapperBuilderCustomizer bmpStrictJson() {
        return builder -> builder.enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter(IdGenerator ids) {
        return registration(new RequestIdFilter(ids), REQUEST_ID_ORDER);
    }

    @Bean
    FilterRegistrationBean<AccessLogFilter> accessLogFilter(Clock clock, WebProperties properties) {
        return registration(new AccessLogFilter(clock, properties.slowRequestThreshold()), ACCESS_LOG_ORDER);
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
            WebProperties properties, ProblemWriter problemWriter) {
        return registration(new RequestSizeLimitFilter(properties.maxRequestBytes(), problemWriter), SIZE_LIMIT_ORDER);
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter() {
        return registration(new IdempotencyFilter(), IDEMPOTENCY_ORDER);
    }

    @Bean
    IdempotencyInterceptor idempotencyInterceptor(
            IdempotencyStore store,
            JsonMapper jsonMapper,
            Clock clock,
            WebProperties properties,
            ObjectProvider<MeterRegistry> meters) {
        return new IdempotencyInterceptor(
                store,
                new RequestHasher(jsonMapper),
                clock,
                properties.idempotencyTtl(),
                meters.getIfUnique(SimpleMeterRegistry::new));
    }

    @Bean
    WebMvcConfigurer bmpInterceptors(IdempotencyInterceptor idempotencyInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ModuleMdcInterceptor());
                registry.addInterceptor(idempotencyInterceptor);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> registration(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        return registration;
    }
}
