package com.bmp.persistence.event;

import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.IdGenerator;
import java.time.Clock;

/** Stamps events with a UUIDv7, the injected clock and the current actor/request/trace metadata. */
public class EventStamps implements EventStampSource {

    private final IdGenerator ids;
    private final Clock clock;
    private final EventMetadataSource metadata;

    public EventStamps(IdGenerator ids, Clock clock, EventMetadataSource metadata) {
        this.ids = ids;
        this.clock = clock;
        this.metadata = metadata;
    }

    @Override
    public EventStamp next() {
        return new EventStamp(ids.newUuid(), clock.instant(), metadata.current());
    }
}
