package com.bmp.persistence.config;

import com.bmp.persistence.event.EventBacklogHealthIndicator;
import com.bmp.persistence.event.EventContext;
import com.bmp.persistence.event.EventContextInterceptor;
import com.bmp.persistence.event.EventMetadataSource;
import com.bmp.persistence.event.EventResubmissionJob;
import com.bmp.persistence.event.EventStamps;
import com.bmp.persistence.event.FailedEventStore;
import com.bmp.shared.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Event listener context, failure parking, resubmission and the outbox health indicator. */
@Configuration(proxyBeanMethods = false)
class EventInfrastructureConfiguration {

    /** Before the transaction advisor (lowest precedence), after the async hop (always first). */
    static final int LISTENER_CONTEXT_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    @Bean
    EventContext eventContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new EventContext(tracer.getIfUnique(), propagator.getIfUnique());
    }

    @Bean
    EventMetadataSource eventMetadataSource(ObjectProvider<Tracer> tracer) {
        return new EventMetadataSource(tracer.getIfUnique());
    }

    @Bean
    EventStamps eventStamps(IdGenerator ids, Clock clock, EventMetadataSource eventMetadataSource) {
        return new EventStamps(ids, clock, eventMetadataSource);
    }

    @Bean
    FailedEventStore failedEventStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper,
            IdGenerator ids,
            Clock clock) {
        return new FailedEventStore(jdbcClient, transactionManager, jsonMapper, ids, clock);
    }

    @Bean
    EventContextInterceptor eventContextInterceptor(
            EventContext eventContext, FailedEventStore failedEventStore, ObjectProvider<MeterRegistry> meters) {
        return new EventContextInterceptor(
                eventContext, failedEventStore, meters.getIfUnique(SimpleMeterRegistry::new));
    }

    /**
     * Applies {@link EventContextInterceptor} to every {@code @ApplicationModuleListener}. The interceptor is looked
     * up lazily so this infrastructure advisor does not force early initialization of application beans.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static DefaultPointcutAdvisor eventContextAdvisor(ObjectProvider<EventContextInterceptor> interceptor) {
        MethodInterceptor lazy = invocation -> interceptor.getObject().invoke(invocation);
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                new AnnotationMatchingPointcut(null, ApplicationModuleListener.class, true), lazy);
        advisor.setOrder(LISTENER_CONTEXT_ORDER);
        return advisor;
    }

    @Bean
    @ConditionalOnBean(FailedEventPublications.class)
    EventResubmissionJob eventResubmissionJob(
            FailedEventPublications failedPublications,
            FailedEventStore failedEventStore,
            ObjectProvider<MeterRegistry> meters,
            Clock clock) {
        return new EventResubmissionJob(
                failedPublications, failedEventStore, meters.getIfUnique(SimpleMeterRegistry::new), clock);
    }

    @Bean(name = "eventBacklog")
    EventBacklogHealthIndicator eventBacklogHealthIndicator(
            JdbcClient jdbcClient,
            Clock clock,
            @Value("${bmp.events.backlog-threshold:100}") long threshold,
            @Value("${bmp.events.backlog-max-age:PT5M}") Duration maxAge) {
        return new EventBacklogHealthIndicator(jdbcClient, clock, threshold, maxAge);
    }
}
