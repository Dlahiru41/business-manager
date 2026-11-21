package com.bmp.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.persistence.event.EventBacklogHealthIndicator;
import com.bmp.persistence.event.EventMetadataSource;
import com.bmp.persistence.jdbc.PlatformAdminJdbc;
import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.test.persistence.WithPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** T00-05: listeners run in the event's tenant (from the start of their transaction), actor and logging context. */
@SpringBootTest(classes = PersistenceTestApplication.class)
@Import(WidgetRenamedListener.class)
@WithPostgres
class EventListenerIT {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    EventMetadataSource metadata;

    @Autowired
    WidgetRenamedListener listener;

    @Autowired
    PlatformAdminJdbc platformAdmin;

    @Autowired
    EventBacklogHealthIndicator eventBacklog;

    private final TenantId tenant = TenantId.of(UUID.randomUUID());

    private WidgetRenamed publish(boolean reject) {
        UserId user = UserId.of(UUID.randomUUID());
        return ActorContext.callWith(
                Actor.user(user),
                () -> com.bmp.shared.context.TenantContext.callWith(
                        tenant,
                        () -> tx.execute(status -> {
                            WidgetRenamed event = new WidgetRenamed(
                                    UUID.randomUUID(),
                                    tenant,
                                    Instant.now(),
                                    UUID.randomUUID(),
                                    1,
                                    metadata.current(),
                                    reject);
                            events.publishEvent(event);
                            return event;
                        })));
    }

    @Test
    void listenerShouldRunWithTheEventsTenantActorAndMdc() {
        WidgetRenamed event = publish(false);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(listener.observed()).anySatisfy(seen -> {
                    assertThat(seen.eventId()).isEqualTo(event.eventId());
                    assertThat(seen.contextTenant()).isEqualTo(tenant);
                    assertThat(seen.databaseTenant()).isEqualTo(tenant.value().toString());
                    assertThat(seen.mdcTenant()).isEqualTo(tenant.toString());
                    assertThat(seen.mdcEventId()).isEqualTo(event.eventId().toString());
                    assertThat(seen.actor()).isEqualTo(event.metadata().actorId());
                    assertThat(seen.firstDelivery()).isTrue();
                }));
    }

    @Test
    void rejectedEventsShouldBeParkedWithTheirProblem() {
        WidgetRenamed event = publish(true);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(platformAdmin
                                .jdbc()
                                .sql("SELECT status FROM platform.failed_event WHERE event_id = :id")
                                .param("id", event.eventId())
                                .query(String.class)
                                .optional())
                        .contains("PARKED"));
        String problem = platformAdmin
                .jdbc()
                .sql("SELECT problem->>'code' FROM platform.failed_event WHERE event_id = :id")
                .param("id", event.eventId())
                .query(String.class)
                .single();
        assertThat(problem).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void eventBacklogShouldBeHealthyWithoutStalePublications() {
        assertThat(eventBacklog.health().getStatus()).isEqualTo(Status.UP);
    }
}
