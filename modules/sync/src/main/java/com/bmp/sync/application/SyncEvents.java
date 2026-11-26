package com.bmp.sync.application;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.sync.api.events.BillNumberGapDetected;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes the sync events inside the transaction that recorded the change (outbox). */
@Component
public class SyncEvents {

    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;

    public SyncEvents(ApplicationEventPublisher publisher, EventStampSource stamps) {
        this.publisher = publisher;
        this.stamps = stamps;
    }

    public void gapDetected(UUID registerId, String billPrefix, List<String> missingNumbers) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new BillNumberGapDetected(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                registerId,
                0,
                stamp.metadata(),
                registerId,
                billPrefix,
                missingNumbers));
    }
}
