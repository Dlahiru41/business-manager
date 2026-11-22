package com.bmp.shared.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.bmp.sample.api.events.SampleThingHappened;
import com.bmp.sample.api.events.SampleThingRenamedV2;
import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainEventsTest {

    record MisplacedEvent(
            UUID eventId,
            TenantId tenantId,
            Instant occurredAt,
            UUID aggregateId,
            long aggregateVersion,
            EventMetadata metadata)
            implements DomainEvent {}

    @Test
    void shouldDeriveExternalTypeNames() {
        assertThat(DomainEvents.typeName(SampleThingHappened.class)).isEqualTo("bmp.sample.sample-thing-happened.v1");
        assertThat(DomainEvents.typeName(SampleThingRenamedV2.class)).isEqualTo("bmp.sample.sample-thing-renamed.v2");
    }

    @Test
    void shouldRejectEventsOutsideApiEventsPackage() {
        assertThatIllegalArgumentException().isThrownBy(() -> DomainEvents.typeName(MisplacedEvent.class));
    }

    @Test
    void recorderShouldDrainInOrderAndClear() {
        EventRecorder recorder = new EventRecorder();
        TenantId tenant = TenantId.of(UUID.randomUUID());
        var first = new SampleThingHappened(
                UUID.randomUUID(), tenant, Instant.EPOCH, UUID.randomUUID(), 1, EventMetadata.empty());
        var second = new SampleThingHappened(
                UUID.randomUUID(), tenant, Instant.EPOCH, UUID.randomUUID(), 2, EventMetadata.empty());

        assertThat(recorder.isEmpty()).isTrue();
        recorder.record(first);
        recorder.record(second);

        assertThat(recorder.isEmpty()).isFalse();
        assertThat(recorder.drain()).containsExactly(first, second);
        assertThat(recorder.drain()).isEmpty();
        assertThat(EventMetadata.empty().actorId()).isNull();
    }
}
