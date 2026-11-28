package com.bmp.finance;

import com.bmp.finance.api.events.JournalPosted;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.id.TenantId;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/** Finance with the tenant module; other modules' events are published by the tests themselves. */
@SpringBootApplication(scanBasePackages = {"com.bmp.finance", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.finance", "com.bmp.tenant"})
@Import(TestJwtConfiguration.class)
public class FinanceTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-25T09:00:00Z");
    }

    @Bean
    SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    FinanceEventRecorder financeEventRecorder() {
        return new FinanceEventRecorder();
    }

    /** Finance events of every thread (the listeners publish on their own). */
    public static class FinanceEventRecorder {

        private final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(JournalPosted event) {
            events.add(event);
        }

        public <T extends DomainEvent> List<T> of(Class<T> type, TenantId tenant) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .filter(e -> e.tenantId().equals(tenant))
                    .toList();
        }
    }
}
