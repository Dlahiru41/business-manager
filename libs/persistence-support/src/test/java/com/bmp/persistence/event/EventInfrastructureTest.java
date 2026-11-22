package com.bmp.persistence.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.MdcKeys;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.test.time.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.modulith.events.AbandonPolicy;
import org.springframework.modulith.events.EventPublication;

class EventInfrastructureTest {

    record SampleHappened(
            UUID eventId,
            TenantId tenantId,
            Instant occurredAt,
            UUID aggregateId,
            long aggregateVersion,
            EventMetadata metadata)
            implements DomainEvent {}

    private static SampleHappened event(EventMetadata metadata) {
        return new SampleHappened(
                UUID.randomUUID(), TenantId.of(UUID.randomUUID()), Instant.EPOCH, UUID.randomUUID(), 1, metadata);
    }

    @Test
    void eventContextShouldBindTenantActorAndMdcThenRestore() throws Throwable {
        UserId user = UserId.of(UUID.randomUUID());
        DeviceId device = DeviceId.of(UUID.randomUUID());
        SampleHappened event = event(new EventMetadata(user, device, "req-1", null));
        AtomicReference<String> seen = new AtomicReference<>();
        MDC.put("requestId", "outer");

        String result = new EventContext(null, null).call(event, EventInfrastructureTest.class, () -> {
            seen.set(TenantContext.require() + "|"
                    + ActorContext.currentOrSystem().userId() + "|"
                    + ActorContext.currentOrSystem().deviceId() + "|" + MDC.get(MdcKeys.TENANT_ID) + "|"
                    + MDC.get(MdcKeys.EVENT_ID) + "|" + MDC.get(MdcKeys.EVENT_TYPE) + "|" + MDC.get(MdcKeys.MODULE));
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(seen.get())
                .isEqualTo(event.tenantId() + "|" + user + "|" + device + "|" + event.tenantId() + "|" + event.eventId()
                        + "|SampleHappened|persistence");
        assertThat(TenantContext.current()).isEmpty();
        assertThat(MDC.get(MdcKeys.EVENT_ID)).isNull();
        assertThat(MDC.get("requestId")).isEqualTo("outer");
        MDC.clear();
    }

    @Test
    void eventContextShouldContinueThePublishersTrace() throws Throwable {
        SimpleTracer tracer = new SimpleTracer();
        var span = tracer.nextSpan().name("publish").start();
        String traceparent;
        try (var _ = tracer.withSpan(span)) {
            traceparent = new EventMetadataSource(tracer).current().traceparent();
        } finally {
            span.end();
        }
        SampleHappened event = event(new EventMetadata(null, null, null, traceparent));

        new EventContext(tracer, null).call(event, EventInfrastructureTest.class, () -> null);

        assertThat(traceparent).startsWith("00-" + span.context().traceId() + "-");
        assertThat(tracer.getSpans()).extracting(s -> s.getName()).contains("event SampleHappened");
    }

    @Test
    void eventContextShouldPropagateListenerFailuresAndStillRestore() {
        SampleHappened event = event(EventMetadata.empty());

        assertThatThrownBy(() -> new EventContext(null, null).call(event, EventInfrastructureTest.class, () -> {
                    throw new IllegalStateException("transient");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void resubmissionShouldBackOffExponentiallyAndAbandonAfterMaxAttempts() {
        MutableClock clock = MutableClock.at("2026-11-21T10:00:00Z");
        EventResubmissionJob job = new EventResubmissionJob(null, null, new SimpleMeterRegistry(), clock);
        Instant lastTry = clock.instant().minus(Duration.ofMinutes(3));

        assertThat(job.isDue(publication(1, lastTry)))
                .as("1 min after 1st attempt")
                .isTrue();
        assertThat(job.isDue(publication(2, lastTry))).as("2 min after 2nd").isTrue();
        assertThat(job.isDue(publication(3, lastTry))).as("4 min after 3rd").isFalse();
        assertThat(job.abandonDecision(publication(EventResubmissionJob.MAX_ATTEMPTS - 1, lastTry)))
                .isEqualTo(AbandonPolicy.Decision.RETAIN);
        assertThat(job.abandonDecision(publication(EventResubmissionJob.MAX_ATTEMPTS, lastTry)))
                .isEqualTo(AbandonPolicy.Decision.ABANDON);
    }

    private static EventPublication publication(int attempts, Instant lastPublication) {
        return new EventPublication() {
            @Override
            public UUID getIdentifier() {
                return UUID.randomUUID();
            }

            @Override
            public Object getEvent() {
                return "not a domain event";
            }

            @Override
            public Instant getPublicationDate() {
                return lastPublication;
            }

            @Override
            public Optional<Instant> getCompletionDate() {
                return Optional.empty();
            }

            @Override
            public Status getStatus() {
                return Status.FAILED;
            }

            @Override
            public Instant getLastResubmissionDate() {
                return lastPublication;
            }

            @Override
            public int getCompletionAttempts() {
                return attempts;
            }
        };
    }
}
